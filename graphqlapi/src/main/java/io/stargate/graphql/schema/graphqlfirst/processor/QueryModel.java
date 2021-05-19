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
package io.stargate.graphql.schema.graphqlfirst.processor;

import graphql.language.FieldDefinition;
import graphql.schema.DataFetcher;
import io.stargate.graphql.schema.graphqlfirst.fetchers.deployed.QueryFetcher;
import java.util.List;
import java.util.Optional;

public class QueryModel extends OperationModel {

  // TODO implement more flexible rules
  // This is a basic implementation that only allows a single-entity SELECT by full primary key.
  // There will probably be significant changes when we support more scenarios: partial primary key
  // (returning multiple entities), index lookups, etc

  private final EntityModel entity;
  private final List<WhereConditionModel> whereConditions;
  private final Optional<String> pagingStateArgumentName;
  private final Optional<Integer> limit;
  private final Optional<Integer> pageSize;
  private final ReturnType returnType;

  QueryModel(
      String parentTypeName,
      FieldDefinition field,
      EntityModel entity,
      List<WhereConditionModel> whereConditions,
      Optional<String> pagingStateArgumentName,
      Optional<Integer> limit,
      Optional<Integer> pageSize,
      ReturnType returnType) {
    super(parentTypeName, field);
    this.entity = entity;
    this.whereConditions = whereConditions;
    this.pagingStateArgumentName = pagingStateArgumentName;
    this.limit = limit;
    this.pageSize = pageSize;
    this.returnType = returnType;
  }

  public EntityModel getEntity() {
    return entity;
  }

  public List<WhereConditionModel> getWhereConditions() {
    return whereConditions;
  }

  /**
   * If the query has an argument that was annotated with {@code @cql_pageState}, the name of that
   * argument.
   */
  public Optional<String> getPagingStateArgumentName() {
    return pagingStateArgumentName;
  }

  public Optional<Integer> getLimit() {
    return limit;
  }

  public Optional<Integer> getPageSize() {
    return pageSize;
  }

  public ReturnType getReturnType() {
    return returnType;
  }

  @Override
  public DataFetcher<?> getDataFetcher(MappingModel mappingModel) {
    return new QueryFetcher(this, mappingModel);
  }
}
