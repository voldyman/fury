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

import com.google.common.base.Preconditions;
import io.fury.Fury;
import io.fury.builder.MetaSharedCodecBuilder;
import io.fury.collection.Tuple2;
import io.fury.collection.Tuple3;
import io.fury.config.CompatibleMode;
import io.fury.config.FuryBuilder;
import io.fury.memory.MemoryBuffer;
import io.fury.resolver.ClassInfoHolder;
import io.fury.resolver.ClassResolver;
import io.fury.resolver.RefResolver;
import io.fury.type.ClassDef;
import io.fury.type.Descriptor;
import io.fury.type.DescriptorGrouper;
import io.fury.type.Generics;
import io.fury.util.FieldAccessor;
import io.fury.util.Platform;
import io.fury.util.ReflectionUtils;
import io.fury.util.record.RecordInfo;
import io.fury.util.record.RecordUtils;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.stream.Collectors;

/**
 * A meta-shared compatible deserializer builder based on {@link ClassDef}. This serializer will
 * compare fields between {@link ClassDef} and class fields, then create serializer to read and
 * set/skip corresponding fields to support type forward/backward compatibility. Serializer are
 * forward to {@link ObjectSerializer} for now. We can consolidate fields between peers to create
 * better serializers to serialize common fields between peers for efficiency.
 *
 * <p>With meta context share enabled and compatible mode, the {@link ObjectSerializer} will take
 * all non-inner final types as non-final, so that fury can write class definition when write class
 * info for those types.
 *
 * @see CompatibleMode
 * @see FuryBuilder#withMetaContextShare
 * @see MetaSharedCodecBuilder
 * @see ObjectSerializer
 * @author chaokunyang
 */
@SuppressWarnings({"unchecked"})
public class MetaSharedSerializer<T> extends Serializer<T> {
  private final ObjectSerializer.FinalTypeField[] finalFields;
  /**
   * Whether write class def for non-inner final types.
   *
   * @see ClassResolver#isFinal(Class)
   */
  private final boolean[] isFinal;

  private final ObjectSerializer.GenericTypeField[] otherFields;
  private final ObjectSerializer.GenericTypeField[] containerFields;
  private final boolean isRecord;
  private final MethodHandle constructor;
  private final RecordInfo recordInfo;
  private Serializer<T> serializer;
  private final ClassInfoHolder classInfoHolder;

  public MetaSharedSerializer(Fury fury, Class<T> type, ClassDef classDef) {
    super(fury, type);
    Preconditions.checkArgument(
        !fury.getConfig().checkClassVersion(),
        "Class version check should be disabled when compatible mode is enabled.");
    Preconditions.checkArgument(fury.getConfig().shareMetaContext(), "Meta share must be enabled.");
    Collection<Descriptor> descriptors = consolidateFields(fury.getClassResolver(), type, classDef);
    DescriptorGrouper descriptorGrouper =
        DescriptorGrouper.createDescriptorGrouper(
            descriptors, true, fury.compressInt(), fury.getConfig().compressLong());
    // d.getField() may be null if not exists in this class when meta share enabled.
    isRecord = RecordUtils.isRecord(type);
    if (isRecord) {
      constructor = RecordUtils.getRecordConstructor(type).f1;
    } else {
      this.constructor = ReflectionUtils.getExecutableNoArgConstructorHandle(type);
    }
    Tuple3<
            Tuple2<ObjectSerializer.FinalTypeField[], boolean[]>,
            ObjectSerializer.GenericTypeField[],
            ObjectSerializer.GenericTypeField[]>
        infos = ObjectSerializer.buildFieldInfos(fury, descriptorGrouper);
    finalFields = infos.f0.f0;
    isFinal = infos.f0.f1;
    otherFields = infos.f1;
    containerFields = infos.f2;
    classInfoHolder = fury.getClassResolver().nilClassInfoHolder();
    if (isRecord) {
      List<String> fieldNames =
          descriptorGrouper.getSortedDescriptors().stream()
              .map(Descriptor::getName)
              .collect(Collectors.toList());
      recordInfo = new RecordInfo(type, fieldNames);
    } else {
      recordInfo = null;
    }
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    if (serializer == null) {
      serializer =
          fury.getClassResolver()
              .createSerializerSafe(type, () -> new ObjectSerializer<>(fury, type));
    }
    serializer.write(buffer, value);
  }

  @Override
  public T read(MemoryBuffer buffer) {
    if (isRecord) {
      Object[] fieldValues =
          new Object[finalFields.length + otherFields.length + containerFields.length];
      readFields(buffer, fieldValues);
      RecordUtils.remapping(recordInfo, fieldValues);
      try {
        T t = (T) constructor.invokeWithArguments(recordInfo.getRecordComponents());
        Arrays.fill(recordInfo.getRecordComponents(), null);
        return t;
      } catch (Throwable e) {
        Platform.throwException(e);
      }
    }
    T obj = ObjectSerializer.newBean(constructor, type);
    Fury fury = this.fury;
    RefResolver refResolver = fury.getRefResolver();
    ClassResolver classResolver = fury.getClassResolver();
    refResolver.reference(obj);
    // read order: primitive,boxed,final,other,collection,map
    ObjectSerializer.FinalTypeField[] finalFields = this.finalFields;
    for (int i = 0; i < finalFields.length; i++) {
      ObjectSerializer.FinalTypeField fieldInfo = finalFields[i];
      boolean isFinal = this.isFinal[i];
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        short classId = fieldInfo.classId;
        if (ObjectSerializer.readPrimitiveFieldValueFailed(
                fury, buffer, obj, fieldAccessor, classId)
            && ObjectSerializer.readBasicObjectFieldValueFailed(
                fury, buffer, obj, fieldAccessor, classId)) {
          assert fieldInfo.classInfo != null;
          Object fieldValue =
              ObjectSerializer.readFinalObjectFieldValue(
                  fury, refResolver, classResolver, fieldInfo, isFinal, buffer);
          fieldAccessor.putObject(obj, fieldValue);
        }
      } else {
        if (skipPrimitiveFieldValueFailed(fury, fieldInfo.classId, buffer)) {
          if (fieldInfo.classInfo == null) {
            // TODO(chaokunyang) support registered serializer in peer with ref tracking disabled.
            fury.readRef(buffer, classInfoHolder);
          } else {
            ObjectSerializer.readFinalObjectFieldValue(
                fury, refResolver, classResolver, fieldInfo, isFinal, buffer);
          }
        }
      }
    }
    for (ObjectSerializer.GenericTypeField fieldInfo : otherFields) {
      Object fieldValue = ObjectSerializer.readOtherFieldValue(fury, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
    Generics generics = fury.getGenerics();
    for (ObjectSerializer.GenericTypeField fieldInfo : containerFields) {
      Object fieldValue =
          ObjectSerializer.readContainerFieldValue(fury, generics, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
    return obj;
  }

  private void readFields(MemoryBuffer buffer, Object[] fields) {
    int counter = 0;
    Fury fury = this.fury;
    RefResolver refResolver = fury.getRefResolver();
    ClassResolver classResolver = fury.getClassResolver();
    // read order: primitive,boxed,final,other,collection,map
    ObjectSerializer.FinalTypeField[] finalFields = this.finalFields;
    for (int i = 0; i < finalFields.length; i++) {
      ObjectSerializer.FinalTypeField fieldInfo = finalFields[i];
      boolean isFinal = this.isFinal[i];
      if (fieldInfo.fieldAccessor != null) {
        assert fieldInfo.classInfo != null;
        short classId = fieldInfo.classId;
        // primitive field won't write null flag.
        if (classId >= ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID
            && classId <= ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID) {
          fields[counter++] = Serializers.readPrimitiveValue(fury, buffer, classId);
        } else {
          Object fieldValue =
              ObjectSerializer.readFinalObjectFieldValue(
                  fury, refResolver, classResolver, fieldInfo, isFinal, buffer);
          fields[counter++] = fieldValue;
        }
      } else {
        if (skipPrimitiveFieldValueFailed(fury, fieldInfo.classId, buffer)) {
          if (fieldInfo.classInfo == null) {
            // TODO(chaokunyang) support registered serializer in peer with ref tracking disabled.
            fury.readRef(buffer, classInfoHolder);
          } else {
            ObjectSerializer.readFinalObjectFieldValue(
                fury, refResolver, classResolver, fieldInfo, isFinal, buffer);
          }
        }
        fields[counter++] = null;
      }
    }
    for (ObjectSerializer.GenericTypeField fieldInfo : otherFields) {
      Object fieldValue = ObjectSerializer.readOtherFieldValue(fury, fieldInfo, buffer);
      fields[counter++] = fieldValue;
    }
    Generics generics = fury.getGenerics();
    for (ObjectSerializer.GenericTypeField fieldInfo : containerFields) {
      Object fieldValue =
          ObjectSerializer.readContainerFieldValue(fury, generics, fieldInfo, buffer);
      fields[counter++] = fieldValue;
    }
  }

  /** Skip primitive primitive field value since it doesn't write null flag. */
  static boolean skipPrimitiveFieldValueFailed(Fury fury, short classId, MemoryBuffer buffer) {
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
        buffer.increaseReaderIndex(1);
        return false;
      case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
        buffer.increaseReaderIndex(1);
        return false;
      case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
        buffer.increaseReaderIndex(2);
        return false;
      case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
        buffer.increaseReaderIndex(2);
        return false;
      case ClassResolver.PRIMITIVE_INT_CLASS_ID:
        if (fury.compressInt()) {
          buffer.readVarInt();
        } else {
          buffer.increaseReaderIndex(4);
        }
        return false;
      case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
        buffer.increaseReaderIndex(4);
        return false;
      case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
        fury.readLong(buffer);
        return false;
      case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
        buffer.increaseReaderIndex(8);
        return false;
      default:
        {
          return true;
        }
    }
  }

  /**
   * Consolidate fields of <code>classDef</code> with <code>cls</code>. If some field exists in
   * <code>cls</code> but not in <code>classDef</code>, it won't be returned in final collection. If
   * some field exists in <code>classDef</code> but not in <code> cls</code>, it will be added to
   * final collection.
   *
   * @param cls class load in current process.
   * @param classDef class definition sent from peer.
   */
  public static Collection<Descriptor> consolidateFields(
      ClassResolver classResolver, Class<?> cls, ClassDef classDef) {
    SortedMap<Field, Descriptor> allDescriptorsMap = classResolver.getAllDescriptorsMap(cls, true);
    Map<String, Descriptor> descriptorsMap = new HashMap<>();
    for (Map.Entry<Field, Descriptor> e : allDescriptorsMap.entrySet()) {
      if (descriptorsMap.put(
              e.getKey().getDeclaringClass().getName() + "." + e.getKey().getName(), e.getValue())
          != null) {
        throw new IllegalStateException("Duplicate key");
      }
    }
    List<Descriptor> descriptors = new ArrayList<>(classDef.getFieldsInfo().size());
    for (ClassDef.FieldInfo fieldInfo : classDef.getFieldsInfo()) {
      Descriptor descriptor =
          descriptorsMap.get(fieldInfo.getDefinedClass() + "." + fieldInfo.getFieldName());
      if (descriptor != null) {
        descriptors.add(descriptor);
      } else {
        descriptors.add(fieldInfo.toDescriptor(classResolver));
      }
    }
    return descriptors;
  }
}
