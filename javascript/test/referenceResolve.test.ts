/*
 * Copyright 2023 The Fury Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { fromUint8Array, alloc, BrowserBuffer } from '../packages/fury/lib/platformBuffer';
import { describe, expect, test } from '@jest/globals';
import Fury, { TypeDescription, InternalSerializerType } from '../packages/fury/index';
import { makeHead } from '../packages/fury/lib/referenceResolver';
import { RefFlags } from '../packages/fury/lib/type';
import { BinaryWriter } from '../packages/fury/lib/writer';
import { BinaryReader } from '../packages/fury/lib/reader';

describe('referenceResolve', () => {
    test('should write head work', () => {
        const fury = new Fury();
        const bin = fury.serialize(7);
        expect(fury.deserialize(bin)).toBe(7)
    });

    test('should make head work when flag positive', () => {
        const head = makeHead(RefFlags.NotNullValueFlag, InternalSerializerType.STRING);
        const writer = BinaryWriter({});
        writer.int24(head);
        const ab = writer.dump();
        const reader = BinaryReader({});
        reader.reset(ab);
        expect(reader.int8()).toBe(RefFlags.NotNullValueFlag);
        expect(reader.int16()).toBe(InternalSerializerType.STRING);
    });

    test('should make head work when flag is zero', () => {
        const head = makeHead(RefFlags.RefValueFlag, InternalSerializerType.STRING);
        const writer = BinaryWriter({});
        writer.int24(head);
        const ab = writer.dump();
        const reader = BinaryReader({});
        reader.reset(ab);
        expect(reader.int8()).toBe(RefFlags.RefValueFlag);
        expect(reader.int16()).toBe(InternalSerializerType.STRING);
    });
});


