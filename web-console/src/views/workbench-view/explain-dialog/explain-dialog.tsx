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

import {
  Button,
  Classes,
  Dialog,
  FormGroup,
  InputGroup,
  Intent,
  Tab,
  Tabs,
  TabsExpander,
} from '@blueprintjs/core';
import { IconNames } from '@blueprintjs/icons';
import * as JSONBig from 'json-bigint-native';
import type { JSX } from 'react';
import React from 'react';
import AceEditor from 'react-ace';

import { Loader } from '../../../components';
import type { DruidEngine, QueryContext, QueryWithContext } from '../../../druid-models';
import { isEmptyContext } from '../../../druid-models';
import { useQueryManager } from '../../../hooks';
import { Api } from '../../../singletons';
import type { QueryExplanation } from '../../../utils';
import {
  deepGet,
  formatColumnMappingsAndSignature,
  getDruidErrorMessage,
  nonEmptyArray,
  queryDruidSql,
  wrapInExplainIfNeeded,
} from '../../../utils';

import './explain-dialog.scss';

export interface QueryContextEngine extends QueryWithContext {
  engine: DruidEngine;
}

export interface ExplainDialogProps {
  queryWithContext: QueryContextEngine;
  mandatoryQueryContext?: Record<string, any>;
  onClose: () => void;
  openQueryLabel: string | undefined;
  onOpenQuery: (queryString: string) => void;
}

export const ExplainDialog = React.memo(function ExplainDialog(props: ExplainDialogProps) {
  const { queryWithContext, onClose, openQueryLabel, onOpenQuery, mandatoryQueryContext } = props;

  const [explainState] = useQueryManager<QueryContextEngine, QueryExplanation[] | string>({
    initQuery: queryWithContext,
    processQuery: async (queryWithContext, cancelToken) => {
      const { engine, queryString, queryContext, wrapQueryLimit } = queryWithContext;

      let context: QueryContext | undefined;
      if (!isEmptyContext(queryContext) || wrapQueryLimit || mandatoryQueryContext) {
        context = {
          ...queryContext,
          ...(mandatoryQueryContext || {}),
          useNativeQueryExplain: true,
        };
        if (typeof wrapQueryLimit !== 'undefined') {
          context.sqlOuterLimit = wrapQueryLimit + 1;
        }
      }

      const payload: any = {
        query: wrapInExplainIfNeeded(queryString),
        context,
      };

      let result: any[];
      switch (engine) {
        case 'sql-native':
        case 'sql-msq-dart':
          result = await queryDruidSql(payload, cancelToken);
          break;

        case 'sql-msq-task':
          try {
            result = (await Api.instance.post(`/druid/v2/sql/task`, payload, { cancelToken })).data;
          } catch (e) {
            throw new Error(getDruidErrorMessage(e));
          }
          break;

        default:
          throw new Error(`Explain not supported for engine ${engine}`);
      }

      const plan = deepGet(result, '0.PLAN');
      if (typeof plan !== 'string') {
        throw new Error(`unexpected result from ${engine} API`);
      }

      try {
        return JSONBig.parse(plan);
      } catch {
        return plan;
      }
    },
  });

  let content: JSX.Element;

  const { loading, error: explainError, data: explainResult } = explainState;

  function renderQueryExplanation(queryExplanation: QueryExplanation) {
    const queryString = JSONBig.stringify(queryExplanation.query, undefined, 2);
    return (
      <div className="query-explanation">
        <FormGroup className="query-group">
          <AceEditor
            mode="hjson"
            theme="solarized_dark"
            className="query-string"
            name="ace-editor"
            fontSize={12}
            width="100%"
            height="100%"
            showGutter
            showPrintMargin={false}
            value={queryString}
            readOnly
          />
        </FormGroup>
        <FormGroup className="signature-group" label="Signature">
          <InputGroup defaultValue={formatColumnMappingsAndSignature(queryExplanation)} readOnly />
        </FormGroup>
        {openQueryLabel && (
          <Button
            className="open-query"
            text={openQueryLabel}
            rightIcon={IconNames.ARROW_TOP_RIGHT}
            intent={Intent.PRIMARY}
            minimal
            onClick={() => {
              onOpenQuery(queryString);
              onClose();
            }}
          />
        )}
      </div>
    );
  }

  if (loading) {
    content = <Loader />;
  } else if (explainError) {
    content = <div>{explainError.message}</div>;
  } else if (!explainResult) {
    content = <div />;
  } else if (nonEmptyArray(explainResult)) {
    if (explainResult.length === 1) {
      content = renderQueryExplanation(explainResult[0]);
    } else {
      content = (
        <Tabs animate renderActiveTabPanelOnly vertical>
          {explainResult.map((queryExplanation, i) => (
            <Tab
              id={i}
              key={i}
              title={`Query ${i + 1}`}
              panel={renderQueryExplanation(queryExplanation)}
            />
          ))}
          <TabsExpander />
        </Tabs>
      );
    }
  } else {
    content = <div className="generic-result">{String(explainResult)}</div>;
  }

  return (
    <Dialog className="explain-dialog" isOpen onClose={onClose} title="Query plan">
      <div className={Classes.DIALOG_BODY}>{content}</div>
      <div className={Classes.DIALOG_FOOTER}>
        <div className={Classes.DIALOG_FOOTER_ACTIONS}>
          <Button text="Close" onClick={onClose} />
        </div>
      </div>
    </Dialog>
  );
});
