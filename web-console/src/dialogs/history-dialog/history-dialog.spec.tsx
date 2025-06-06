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

import { render } from '@testing-library/react';
import * as JSONBig from 'json-bigint-native';

import { HistoryDialog } from './history-dialog';

describe('HistoryDialog', () => {
  it('matches snapshot', () => {
    const historyDialog = (
      <HistoryDialog
        title="History"
        historyRecords={[
          {
            auditTime: '2025-04-03T02:01:00.000Z',
            auditInfo: { comment: 'test' },
            payload: JSONBig.stringify({ name: 'test' }),
          },
          {
            auditTime: '2025-04-03T01:01:00.000Z',
            auditInfo: { comment: 'test' },
            payload: JSONBig.stringify({ name: 'test' }),
          },
        ]}
        onBack={() => {}}
      />
    );
    render(historyDialog);
    expect(document.body.lastChild).toMatchSnapshot();
  });
});
