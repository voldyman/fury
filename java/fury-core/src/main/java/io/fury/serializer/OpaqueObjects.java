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

package io.fury.serializer;

import io.fury.config.Language;

/**
 * Stub objects for unsupported cross-language serializing type.
 *
 * @author chaokunyang
 */
public class OpaqueObjects {

  public static OpaqueObject of(Language language, String className, int ordinal) {
    return new OpaqueObject(language, className, ordinal);
  }

  public static class OpaqueObject {
    private final Language language;
    private final String className;
    private final int ordinal;

    public OpaqueObject(Language language, String className, int ordinal) {
      this.language = language;
      this.className = className;
      this.ordinal = ordinal;
    }

    public Language language() {
      return language;
    }

    public String className() {
      return className;
    }

    public int ordinal() {
      return ordinal;
    }
  }
}
