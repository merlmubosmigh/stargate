/*
 * Copyright The Stargate Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stargate.grpc.payload.cql;

import static io.stargate.grpc.codec.cql.ValueCodec.decodeValue;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.InvalidProtocolBufferException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.Status;
import io.grpc.StatusException;
import io.stargate.db.BoundStatement;
import io.stargate.db.Result.Prepared;
import io.stargate.db.Result.Rows;
import io.stargate.db.schema.Column;
import io.stargate.db.schema.Column.ColumnType;
import io.stargate.db.schema.UserDefinedType;
import io.stargate.grpc.codec.cql.ValueCodec;
import io.stargate.grpc.codec.cql.ValueCodecs;
import io.stargate.grpc.payload.PayloadHandler;
import io.stargate.proto.QueryOuterClass.ColumnSpec;
import io.stargate.proto.QueryOuterClass.QueryParameters;
import io.stargate.proto.QueryOuterClass.ResultSet;
import io.stargate.proto.QueryOuterClass.Row;
import io.stargate.proto.QueryOuterClass.TypeSpec;
import io.stargate.proto.QueryOuterClass.Value;
import io.stargate.proto.QueryOuterClass.Values;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ValuesHandler implements PayloadHandler {
  @Override
  public BoundStatement bindValues(Prepared prepared, Any payload, ByteBuffer unsetValue)
      throws InvalidProtocolBufferException, StatusException {
    final Values values = payload.unpack(Values.class);
    final List<Column> columns = prepared.metadata.columns;
    final int columnCount = columns.size();
    final int valuesCount = values.getValuesCount();
    if (columnCount != valuesCount) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              String.format(
                  "Invalid number of bind values. Expected %d, but received %d",
                  columnCount, valuesCount))
          .asException();
    }
    final List<ByteBuffer> boundValues = new ArrayList<>(columnCount);
    List<String> boundValueNames = null;
    if (values.getValueNamesCount() != 0) {
      final int namesCount = values.getValueNamesCount();
      if (namesCount != columnCount) {
        throw Status.FAILED_PRECONDITION
            .withDescription(
                String.format(
                    "Invalid number of bind names. Expected %d, but received %d",
                    columnCount, namesCount))
            .asException();
      }
      boundValueNames = new ArrayList<>(namesCount);
      for (int i = 0; i < namesCount; ++i) {
        String name = values.getValueNames(i);
        Column column =
            columns.stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(
                    () ->
                        Status.INVALID_ARGUMENT
                            .withDescription(
                                String.format("Unable to find bind marker with name '%s'", name))
                            .asException());
        ColumnType columnType = columnTypeNotNull(column);
        ValueCodec codec = ValueCodecs.get(columnType.rawType());
        Value value = values.getValues(i);
        try {
          boundValues.add(encodeValue(codec, value, columnType, unsetValue));
        } catch (Exception e) {
          throw Status.INVALID_ARGUMENT
              .withDescription(
                  String.format("Invalid argument for name '%s': %s", name, e.getMessage()))
              .withCause(e)
              .asException();
        }
        boundValueNames.add(name);
      }
    } else {
      for (int i = 0; i < columnCount; ++i) {
        Column column = columns.get(i);
        Value value = values.getValues(i);
        ColumnType columnType = columnTypeNotNull(column);
        ValueCodec codec = ValueCodecs.get(columnType.rawType());
        try {
          boundValues.add(encodeValue(codec, value, columnType, unsetValue));
        } catch (Exception e) {
          throw Status.INVALID_ARGUMENT
              .withDescription(
                  String.format("Invalid argument at position %d: %s", i + 1, e.getMessage()))
              .withCause(e)
              .asException();
        }
      }
    }

    return new BoundStatement(prepared.statementId, boundValues, boundValueNames);
  }

  @Override
  public Any processResult(Rows rows, QueryParameters parameters) throws StatusException {
    final List<Column> columns = rows.resultMetadata.columns;
    final int columnCount = columns.size();

    ResultSet.Builder resultSetBuilder = ResultSet.newBuilder();

    if (!parameters.getSkipMetadata()) {
      for (Column column : columns) {
        resultSetBuilder.addColumns(
            ColumnSpec.newBuilder()
                .setType(convertType(columnTypeNotNull(column)))
                .setName(column.name())
                .build());
      }
    }

    for (List<ByteBuffer> row : rows.rows) {
      Row.Builder rowBuilder = Row.newBuilder();
      for (int i = 0; i < columnCount; ++i) {
        ColumnType columnType = columnTypeNotNull(columns.get(i));
        ValueCodec codec = ValueCodecs.get(columnType.rawType());
        rowBuilder.addValues(decodeValue(codec, row.get(i), columnType));
      }
      resultSetBuilder.addRows(rowBuilder);
    }

    if (rows.resultMetadata.pagingState != null) {
      resultSetBuilder.setPagingState(
          BytesValue.newBuilder()
              .setValue(ByteString.copyFrom(rows.resultMetadata.pagingState))
              .build());
      resultSetBuilder.setPageSize(Int32Value.newBuilder().setValue(rows.rows.size()).build());
    }

    return Any.pack(resultSetBuilder.build());
  }

  @Nullable
  private ByteBuffer encodeValue(
      ValueCodec codec, Value value, ColumnType columnType, ByteBuffer unsetValue) {
    if (value.hasUnset()) {
      return unsetValue;
    } else {
      return ValueCodec.encodeValue(codec, value, columnType);
    }
  }

  @NonNull
  public static ColumnType columnTypeNotNull(Column column) throws StatusException {
    ColumnType type = column.type();
    if (type == null) {
      throw Status.INTERNAL
          .withDescription(String.format("Column '%s' doesn't have a valid type", column.name()))
          .asException();
    }
    return type;
  }

  public static TypeSpec convertType(ColumnType columnType) throws StatusException {
    TypeSpec.Builder builder = TypeSpec.newBuilder();

    if (columnType.isParameterized()) {
      List<ColumnType> parameters = columnType.parameters();

      switch (columnType.rawType()) {
        case List:
          if (parameters.size() != 1) {
            throw Status.FAILED_PRECONDITION
                .withDescription("Expected list type to have a parameterized type")
                .asException();
          }
          builder.setList(
              TypeSpec.List.newBuilder().setElement(convertType(parameters.get(0))).build());
          break;
        case Map:
          if (parameters.size() != 2) {
            throw Status.FAILED_PRECONDITION
                .withDescription("Expected map type to have key/value parameterized types")
                .asException();
          }
          builder.setMap(
              TypeSpec.Map.newBuilder()
                  .setKey(convertType(parameters.get(0)))
                  .setValue(convertType(parameters.get(1)))
                  .build());
          break;
        case Set:
          if (parameters.size() != 1) {
            throw Status.FAILED_PRECONDITION
                .withDescription("Expected set type to have a parameterized type")
                .asException();
          }
          builder.setSet(
              TypeSpec.Set.newBuilder().setElement(convertType(parameters.get(0))).build());
          break;
        case Tuple:
          if (parameters.isEmpty()) {
            throw Status.FAILED_PRECONDITION
                .withDescription("Expected tuple type to have at least one parameterized type")
                .asException();
          }
          TypeSpec.Tuple.Builder tupleBuilder = TypeSpec.Tuple.newBuilder();
          for (ColumnType parameter : parameters) {
            tupleBuilder.addElements(convertType(parameter));
          }
          builder.setTuple(tupleBuilder.build());
          break;
        case UDT:
          UserDefinedType udt = (UserDefinedType) columnType;
          if (udt.columns().isEmpty()) {
            throw Status.FAILED_PRECONDITION
                .withDescription("Expected user defined type to have at least one field")
                .asException();
          }
          TypeSpec.Udt.Builder udtBuilder = TypeSpec.Udt.newBuilder();
          for (Column column : udt.columns()) {
            udtBuilder.putFields(column.name(), convertType(columnTypeNotNull(column).rawType()));
          }
          builder.setUdt(udtBuilder.build());
          break;
        default:
          throw new AssertionError("Unhandled parameterized type");
      }
    } else {
      builder.setBasic(TypeSpec.Basic.forNumber(columnType.id()));
    }

    return builder.build();
  }
}