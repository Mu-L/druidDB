/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type { TreeNodeInfo } from '@blueprintjs/core';
import {
  Button,
  ButtonGroup,
  Classes,
  HTMLSelect,
  Icon,
  InputGroup,
  Menu,
  MenuDivider,
  MenuItem,
  Popover,
  Position,
  Tree,
} from '@blueprintjs/core';
import { IconNames } from '@blueprintjs/icons';
import type { SqlExpression } from 'druid-query-toolkit';
import {
  C,
  F,
  N,
  SqlColumn,
  SqlComparison,
  SqlJoinPart,
  SqlQuery,
  SqlTable,
  T,
} from 'druid-query-toolkit';
import type { ChangeEvent } from 'react';
import React from 'react';

import { Deferred, Loader } from '../../../components';
import type { ColumnMetadata } from '../../../utils';
import {
  copyAndAlert,
  dataTypeToIcon,
  groupBy,
  oneOf,
  prettyPrintSql,
  tickIcon,
} from '../../../utils';

import {
  ComplexMenuItems,
  NumberMenuItems,
  StringMenuItems,
  TimeMenuItems,
} from './column-tree-menu';

import './column-tree.scss';

const COUNT_STAR = F.count().as('Count');

function getCountExpression(columnNames: string[]): SqlExpression {
  for (const columnName of columnNames) {
    if (columnName === 'count' || columnName === '__count') {
      return F.sum(C(columnName)).as('Count');
    }
  }
  return COUNT_STAR;
}

const STRING_QUERY = SqlQuery.parse(`SELECT
  ?
FROM ?
GROUP BY 1
ORDER BY 2 DESC`);

const TIME_QUERY = SqlQuery.parse(`SELECT
  TIME_FLOOR(?, 'PT1H') AS "Time"
FROM ?
GROUP BY 1
ORDER BY 1 ASC`);

interface HandleColumnClickOptions {
  columnSchema: string;
  columnTable: string;
  columnName: string;
  columnType: string;
  parsedQuery: SqlQuery | undefined;
  defaultWhere: SqlExpression | undefined;
  onQueryChange: (query: SqlQuery, run: boolean) => void;
}

type SearchMode = 'tables-and-columns' | 'tables-only' | 'columns-only';

const SEARCH_MODES: SearchMode[] = ['tables-and-columns', 'tables-only', 'columns-only'];

const SEARCH_MODE_TITLE: Record<SearchMode, string> = {
  'tables-and-columns': 'Tables and columns',
  'tables-only': 'Tables only',
  'columns-only': 'Columns only',
};

function handleColumnShow(options: HandleColumnClickOptions): void {
  const {
    columnSchema,
    columnTable,
    columnName,
    columnType,
    parsedQuery,
    defaultWhere,
    onQueryChange,
  } = options;

  let from: SqlExpression;
  let where: SqlExpression | undefined;
  let aggregates: SqlExpression[] = [];
  if (parsedQuery && parsedQuery.getFirstTableName() === columnTable) {
    from = parsedQuery.getFirstFromExpression()!;
    where = parsedQuery.getWhereExpression();
    aggregates = parsedQuery.getAggregateSelectExpressions();
  } else if (columnSchema === 'druid') {
    from = T(columnTable);
    where = defaultWhere;
  } else {
    from = N(columnSchema).table(columnTable);
  }

  if (!aggregates.length) {
    aggregates.push(COUNT_STAR);
  }

  const column = C(columnName);
  let query: SqlQuery;
  if (columnSchema === 'druid' && columnType === 'TIMESTAMP') {
    query = TIME_QUERY.fillPlaceholders([column, from]) as SqlQuery;
  } else {
    query = STRING_QUERY.fillPlaceholders([column, from]) as SqlQuery;
  }

  let newSelectExpressions = query.selectExpressions;
  if (newSelectExpressions) {
    for (const aggregate of aggregates) {
      newSelectExpressions = newSelectExpressions.append(aggregate);
    }
  }

  onQueryChange(
    query.changeSelectExpressions(newSelectExpressions).changeWhereExpression(where),
    true,
  );
}

export interface ColumnTreeProps {
  columnMetadataLoading: boolean;
  columnMetadata?: readonly ColumnMetadata[];
  getParsedQuery: () => SqlQuery | undefined;
  defaultWhere?: SqlExpression;
  onQueryChange: (query: SqlQuery, run?: boolean) => void;
  defaultSchema?: string;
  defaultTables?: string[];
  highlightTable?: string;
}

export interface ColumnTreeState {
  prevColumnMetadata?: readonly ColumnMetadata[];
  columnTree?: TreeNodeInfo[];
  currentSchemaSubtree?: TreeNodeInfo[];
  selectedTreeIndex: number;
  searchString: string;
  searchMode: SearchMode;
  prevSearchHash?: string;
  expandedTables: Map<string, boolean>;
  prevExpandedTables?: Map<string, boolean>;
}

function computeSearchHash(searchString: string, searchMode: SearchMode): string {
  if (!searchString) return '';
  return `${searchString.toLowerCase()}_${searchMode}`;
}

export function getJoinColumns(parsedQuery: SqlQuery, _table: string) {
  let lookupColumn: string | undefined;
  let originalTableColumn: string | undefined;
  if (parsedQuery.fromClause && parsedQuery.fromClause.joinParts) {
    const firstOnExpression = parsedQuery.fromClause.joinParts.first().onExpression;
    if (firstOnExpression instanceof SqlComparison && firstOnExpression.op === '=') {
      const { lhs, rhs } = firstOnExpression;
      if (lhs instanceof SqlColumn && lhs.getNamespaceName() === 'lookup') {
        lookupColumn = lhs.getName();
      }
      if (rhs instanceof SqlColumn) {
        originalTableColumn = rhs.getName();
      }
    }
  }

  return {
    lookupColumn: lookupColumn || 'k',
    originalTableColumn: originalTableColumn || 'XXX',
  };
}

export class ColumnTree extends React.PureComponent<ColumnTreeProps, ColumnTreeState> {
  static getDerivedStateFromProps(props: ColumnTreeProps, state: ColumnTreeState) {
    const { columnMetadata, defaultSchema, defaultWhere, onQueryChange, highlightTable } = props;
    const { searchString, searchMode, expandedTables, prevExpandedTables } = state;
    const searchHash = computeSearchHash(searchString, searchMode);

    if (
      columnMetadata &&
      (columnMetadata !== state.prevColumnMetadata ||
        searchHash !== state.prevSearchHash ||
        expandedTables !== prevExpandedTables)
    ) {
      const lowerSearchString = searchString.toLowerCase();
      const isSearching = Boolean(lowerSearchString);
      const columnTree = groupBy(
        columnMetadata,
        r => r.TABLE_SCHEMA,
        (metadata, schemaName): TreeNodeInfo => ({
          id: schemaName,
          label: schemaName,
          childNodes: groupBy(
            isSearching
              ? metadata.filter(
                  r =>
                    (searchMode === 'tables-and-columns' &&
                      (r.TABLE_NAME.toLowerCase().includes(lowerSearchString) ||
                        r.COLUMN_NAME.toLowerCase().includes(lowerSearchString))) ||
                    (searchMode === 'tables-only' &&
                      r.TABLE_NAME.toLowerCase().includes(lowerSearchString)) ||
                    (searchMode === 'columns-only' &&
                      r.COLUMN_NAME.toLowerCase().includes(lowerSearchString)),
                )
              : metadata,
            r => r.TABLE_NAME,
            (metadata, tableName): TreeNodeInfo => ({
              id: tableName,
              icon: IconNames.TH,
              className: tableName === highlightTable ? 'highlight' : undefined,
              isExpanded:
                expandedTables.has(tableName) ||
                (isSearching &&
                  (searchMode === 'columns-only' ||
                    !tableName.toLowerCase().includes(lowerSearchString))),
              label: (
                <Popover
                  position={Position.RIGHT}
                  content={
                    <Deferred
                      content={() => {
                        const parsedQuery = props.getParsedQuery();
                        const tableRef = T(tableName);
                        const prettyTableRef = prettyPrintSql(tableRef);
                        const countExpression = getCountExpression(
                          metadata.map(child => child.COLUMN_NAME),
                        );

                        const getQueryOnTable = () => {
                          return SqlQuery.selectStarFrom(
                            SqlTable.create(
                              tableName,
                              schemaName === 'druid' ? undefined : schemaName,
                            ),
                          );
                        };

                        const getWhere = (defaultToAllTime = false) => {
                          if (parsedQuery && parsedQuery.getFirstTableName() === tableName) {
                            return parsedQuery.getWhereExpression();
                          } else if (schemaName === 'druid') {
                            return defaultToAllTime ? undefined : defaultWhere;
                          } else {
                            return;
                          }
                        };

                        return (
                          <Menu>
                            <MenuItem
                              icon={IconNames.FULLSCREEN}
                              text={`SELECT ...columns... FROM ${tableName}`}
                              onClick={() => {
                                onQueryChange(
                                  getQueryOnTable()
                                    .changeSelectExpressions(
                                      metadata
                                        .map(child => child.COLUMN_NAME)
                                        .map(columnName => C(columnName)),
                                    )
                                    .changeWhereExpression(getWhere()),
                                  true,
                                );
                              }}
                            />
                            <MenuItem
                              icon={IconNames.FULLSCREEN}
                              text={`SELECT * FROM ${tableName}`}
                              onClick={() => {
                                onQueryChange(
                                  getQueryOnTable().changeWhereExpression(getWhere()),
                                  true,
                                );
                              }}
                            />
                            <MenuItem
                              icon={IconNames.FULLSCREEN}
                              text={`SELECT ${countExpression} FROM ${tableName}`}
                              onClick={() => {
                                onQueryChange(
                                  getQueryOnTable()
                                    .changeSelect(0, countExpression)
                                    .changeGroupByExpressions([])
                                    .changeWhereExpression(getWhere(true)),
                                  true,
                                );
                              }}
                            />
                            <MenuItem
                              icon={IconNames.FULLSCREEN}
                              text={`SELECT MIN(__time), MAX(__time) FROM ${tableName}`}
                              onClick={() => {
                                onQueryChange(
                                  getQueryOnTable()
                                    .changeSelectExpressions([
                                      F.min(C('__time')).as('min_time'),
                                      F.max(C('__time')).as('max_time'),
                                    ])
                                    .changeGroupByExpressions([])
                                    .changeWhereExpression(getWhere(true))
                                    .removeColumnFromWhere('__time'),
                                  true,
                                );
                              }}
                            />
                            {parsedQuery && parsedQuery.getFirstTableName() !== tableName && (
                              <MenuItem
                                icon={IconNames.EXCHANGE}
                                text={`Replace FROM with: ${prettyTableRef}`}
                                onClick={() => {
                                  onQueryChange(
                                    parsedQuery.changeFromExpressions([tableRef]),
                                    true,
                                  );
                                }}
                              />
                            )}
                            {parsedQuery && schemaName === 'lookup' && (
                              <MenuItem
                                popoverProps={{ openOnTargetFocus: false }}
                                icon={IconNames.JOIN_TABLE}
                                text={parsedQuery.hasJoin() ? `Replace join` : `Join`}
                              >
                                <MenuItem
                                  icon={IconNames.LEFT_JOIN}
                                  text="Left join"
                                  onClick={() => {
                                    const { lookupColumn, originalTableColumn } = getJoinColumns(
                                      parsedQuery,
                                      tableName,
                                    );
                                    onQueryChange(
                                      parsedQuery
                                        .removeAllJoins()
                                        .addJoin(
                                          SqlJoinPart.create(
                                            'LEFT',
                                            N(schemaName).table(tableName),
                                            N('lookup')
                                              .table(tableName)
                                              .column(lookupColumn)
                                              .equal(
                                                SqlColumn.create(
                                                  originalTableColumn,
                                                  parsedQuery.getFirstTableName(),
                                                ),
                                              ),
                                          ),
                                        ),
                                      false,
                                    );
                                  }}
                                />
                                <MenuItem
                                  icon={IconNames.INNER_JOIN}
                                  text="Inner join"
                                  onClick={() => {
                                    const { lookupColumn, originalTableColumn } = getJoinColumns(
                                      parsedQuery,
                                      tableName,
                                    );
                                    onQueryChange(
                                      parsedQuery.addJoin(
                                        SqlJoinPart.create(
                                          'INNER',
                                          N(schemaName).table(tableName),
                                          N('lookup')
                                            .table(tableName)
                                            .column(lookupColumn)
                                            .equal(
                                              SqlColumn.create(
                                                originalTableColumn,
                                                parsedQuery.getFirstTableName(),
                                              ),
                                            ),
                                        ),
                                      ),
                                      false,
                                    );
                                  }}
                                />
                              </MenuItem>
                            )}
                            {parsedQuery &&
                              parsedQuery.hasJoin() &&
                              parsedQuery.getJoins()[0].table.toString() === tableName && (
                                <MenuItem
                                  icon={IconNames.EXCHANGE}
                                  text="Remove join"
                                  onClick={() => onQueryChange(parsedQuery.removeAllJoins())}
                                />
                              )}
                            {parsedQuery &&
                              parsedQuery.hasGroupBy() &&
                              parsedQuery.getFirstTableName() === tableName && (
                                <MenuItem
                                  icon={IconNames.FUNCTION}
                                  text="Aggregate COUNT(*)"
                                  onClick={() =>
                                    onQueryChange(parsedQuery.addSelect(COUNT_STAR), true)
                                  }
                                />
                              )}
                            <MenuItem
                              icon={IconNames.CLIPBOARD}
                              text={`Copy: ${prettyTableRef}`}
                              onClick={() => {
                                copyAndAlert(
                                  tableRef.toString(),
                                  `${prettyTableRef} query copied to clipboard`,
                                );
                              }}
                            />
                          </Menu>
                        );
                      }}
                    />
                  }
                >
                  {tableName}
                </Popover>
              ),
              childNodes: metadata.map(
                (columnData): TreeNodeInfo => ({
                  id: columnData.COLUMN_NAME,
                  icon: (
                    <Icon
                      className={Classes.TREE_NODE_ICON}
                      icon={dataTypeToIcon(columnData.DATA_TYPE)}
                      aria-hidden
                      tabIndex={-1}
                      data-tooltip={columnData.DATA_TYPE}
                    />
                  ),
                  label: (
                    <Popover
                      position={Position.RIGHT}
                      autoFocus={false}
                      content={
                        <Deferred
                          content={() => {
                            const parsedQuery = props.getParsedQuery();
                            return (
                              <Menu>
                                <MenuItem
                                  icon={IconNames.FULLSCREEN}
                                  text={`Show: ${columnData.COLUMN_NAME}`}
                                  onClick={() => {
                                    handleColumnShow({
                                      columnSchema: schemaName,
                                      columnTable: tableName,
                                      columnName: columnData.COLUMN_NAME,
                                      columnType: columnData.DATA_TYPE,
                                      parsedQuery,
                                      defaultWhere,
                                      onQueryChange: onQueryChange,
                                    });
                                  }}
                                />
                                {parsedQuery &&
                                  oneOf(columnData.DATA_TYPE, 'BIGINT', 'FLOAT', 'DOUBLE') && (
                                    <NumberMenuItems
                                      table={tableName}
                                      schema={schemaName}
                                      columnName={columnData.COLUMN_NAME}
                                      parsedQuery={parsedQuery}
                                      onQueryChange={onQueryChange}
                                    />
                                  )}
                                {parsedQuery && columnData.DATA_TYPE === 'VARCHAR' && (
                                  <StringMenuItems
                                    table={tableName}
                                    schema={schemaName}
                                    columnName={columnData.COLUMN_NAME}
                                    parsedQuery={parsedQuery}
                                    onQueryChange={onQueryChange}
                                  />
                                )}
                                {parsedQuery && columnData.DATA_TYPE === 'TIMESTAMP' && (
                                  <TimeMenuItems
                                    table={tableName}
                                    schema={schemaName}
                                    columnName={columnData.COLUMN_NAME}
                                    parsedQuery={parsedQuery}
                                    onQueryChange={onQueryChange}
                                  />
                                )}
                                {parsedQuery && columnData.DATA_TYPE.startsWith('COMPLEX<') && (
                                  <ComplexMenuItems
                                    table={tableName}
                                    schema={schemaName}
                                    columnName={columnData.COLUMN_NAME}
                                    columnType={columnData.DATA_TYPE}
                                    parsedQuery={parsedQuery}
                                    onQueryChange={onQueryChange}
                                  />
                                )}
                                <MenuItem
                                  icon={IconNames.CLIPBOARD}
                                  text={`Copy: ${columnData.COLUMN_NAME}`}
                                  onClick={() => {
                                    copyAndAlert(
                                      columnData.COLUMN_NAME,
                                      `${columnData.COLUMN_NAME} query copied to clipboard`,
                                    );
                                  }}
                                />
                              </Menu>
                            );
                          }}
                        />
                      }
                    >
                      {columnData.COLUMN_NAME}
                    </Popover>
                  ),
                }),
              ),
            }),
          ),
        }),
      );

      let selectedTreeIndex = -1;
      if (defaultSchema && columnTree) {
        selectedTreeIndex = columnTree.findIndex(x => {
          return x.id === defaultSchema;
        });
      }

      if (!columnTree) return null;
      const currentSchemaSubtree =
        columnTree[selectedTreeIndex > -1 ? selectedTreeIndex : 0].childNodes;
      if (!currentSchemaSubtree) return null;

      return {
        prevColumnMetadata: columnMetadata,
        columnTree,
        selectedTreeIndex,
        currentSchemaSubtree,
        prevSearchHash: searchHash,
        prevExpandedTables: expandedTables,
      };
    }
    return null;
  }

  constructor(props: ColumnTreeProps) {
    super(props);
    this.state = {
      selectedTreeIndex: -1,
      searchString: '',
      searchMode: 'tables-and-columns',
      expandedTables: new Map((props.defaultTables || []).map(t => [t, true])),
    };
  }

  private renderSchemaSelector() {
    const { columnTree, selectedTreeIndex } = this.state;

    return (
      <HTMLSelect
        className="schema-selector"
        value={selectedTreeIndex > -1 ? selectedTreeIndex : undefined}
        onChange={this.handleSchemaSelectorChange}
        fill
        minimal
        large
      >
        {columnTree?.map((treeNode, i) => (
          <option key={i} value={i}>
            {treeNode.label}
          </option>
        ))}
      </HTMLSelect>
    );
  }

  private renderSearch() {
    const { searchString, searchMode } = this.state;

    return (
      <InputGroup
        className="search-box"
        placeholder="Search"
        value={searchString}
        onChange={e => {
          this.setState({ searchString: e.target.value.substring(0, 100) });
        }}
        rightElement={
          <ButtonGroup minimal>
            {searchString !== '' && (
              <Button icon={IconNames.CROSS} onClick={() => this.setState({ searchString: '' })} />
            )}
            <Popover
              position="bottom-left"
              content={
                <Menu>
                  <MenuDivider title="Search in" />
                  {SEARCH_MODES.map(mode => (
                    <MenuItem
                      key={mode}
                      icon={tickIcon(mode === searchMode)}
                      text={SEARCH_MODE_TITLE[mode]}
                      onClick={() => this.setState({ searchMode: mode })}
                    />
                  ))}
                </Menu>
              }
            >
              <Button icon={IconNames.SETTINGS} data-tooltip="Search settings" />
            </Popover>
          </ButtonGroup>
        }
      />
    );
  }

  private readonly handleSchemaSelectorChange = (e: ChangeEvent<HTMLSelectElement>): void => {
    const { columnTree } = this.state;
    if (!columnTree) return;

    const selectedTreeIndex = Number(e.target.value);

    const currentSchemaSubtree =
      columnTree[selectedTreeIndex > -1 ? selectedTreeIndex : 0].childNodes;

    this.setState({
      selectedTreeIndex: Number(e.target.value),
      currentSchemaSubtree: currentSchemaSubtree,
    });
  };

  private readonly handleNodeCollapse = (nodeData: TreeNodeInfo) => {
    const expandedTables = new Map(this.state.expandedTables);
    expandedTables.delete(String(nodeData.id));
    this.setState({
      expandedTables,
    });
  };

  private readonly handleNodeExpand = (nodeData: TreeNodeInfo) => {
    const expandedTables = new Map(this.state.expandedTables);
    expandedTables.set(String(nodeData.id), true);
    this.setState({
      expandedTables,
    });
  };

  render() {
    const { columnMetadata, columnMetadataLoading } = this.props;
    const { currentSchemaSubtree, searchString } = this.state;

    if (columnMetadataLoading && !columnMetadata) {
      return (
        <div className="column-tree">
          <Loader />
        </div>
      );
    }

    if (!currentSchemaSubtree) return null;

    return (
      <div className="column-tree">
        {this.renderSchemaSelector()}
        {this.renderSearch()}
        <div className="tree-container">
          {currentSchemaSubtree.length ? (
            <Tree
              contents={currentSchemaSubtree}
              onNodeCollapse={this.handleNodeCollapse}
              onNodeExpand={this.handleNodeExpand}
            />
          ) : (
            <div className="message-box">
              {searchString ? 'The search returned no results' : 'No tables'}
            </div>
          )}
        </div>
      </div>
    );
  }
}
