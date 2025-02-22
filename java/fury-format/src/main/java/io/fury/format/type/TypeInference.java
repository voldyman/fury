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

package io.fury.format.type;

import static io.fury.format.type.DataTypes.field;
import static io.fury.type.TypeUtils.getRawType;

import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;
import com.google.common.reflect.TypeToken;
import io.fury.collection.Tuple2;
import io.fury.type.Descriptor;
import io.fury.type.TypeUtils;
import io.fury.util.DecimalUtils;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Arrow related type inference.
 *
 * @author chaokunyang
 */
@SuppressWarnings("UnstableApiUsage")
public class TypeInference {

  public static Schema inferSchema(java.lang.reflect.Type type) {
    return inferSchema(TypeToken.of(type));
  }

  public static Schema inferSchema(Class<?> clz) {
    return inferSchema(TypeToken.of(clz));
  }

  /**
   * Infer the schema for class.
   *
   * @param typeToken bean class type
   * @return schema of a class
   */
  public static Schema inferSchema(TypeToken<?> typeToken) {
    return inferSchema(typeToken, true);
  }

  public static Schema inferSchema(TypeToken<?> typeToken, boolean forStruct) {
    Field field = inferField(typeToken);
    if (forStruct) {
      Preconditions.checkArgument(field.getType().getTypeID() == ArrowType.ArrowTypeID.Struct);
      return new Schema(field.getChildren());
    } else {
      return new Schema(Arrays.asList(field));
    }
  }

  public static Optional<ArrowType> getDataType(Class<?> cls) {
    return getDataType(TypeToken.of(cls));
  }

  public static Optional<ArrowType> getDataType(TypeToken<?> typeToken) {
    try {
      return Optional.of(inferDataType(typeToken));
    } catch (UnsupportedOperationException e) {
      return Optional.empty();
    }
  }

  public static ArrowType inferDataType(TypeToken<?> typeToken) {
    return inferField(typeToken).getType();
  }

  public static Field arrayInferField(
      java.lang.reflect.Type arrayType, java.lang.reflect.Type type) {
    return arrayInferField(TypeToken.of(arrayType), TypeToken.of(type));
  }

  public static Field arrayInferField(Class<?> arrayClz, Class<?> clz) {
    return arrayInferField(TypeToken.of(arrayClz), TypeToken.of(clz));
  }

  /**
   * Infer the field of the list.
   *
   * @param typeToken bean class type
   * @return field of the list
   */
  public static Field arrayInferField(TypeToken<?> arrayTypeToken, TypeToken<?> typeToken) {
    Field field = inferField(arrayTypeToken, typeToken);
    Preconditions.checkArgument(field.getType().getTypeID() == ArrowType.ArrowTypeID.List);
    return field;
  }

  private static Field inferField(TypeToken<?> typeToken) {
    return inferField(null, typeToken);
  }

  private static Field inferField(TypeToken<?> arrayTypeToken, TypeToken<?> typeToken) {
    LinkedHashSet<Class<?>> seenTypeSet = new LinkedHashSet<>();
    String name = "";
    if (arrayTypeToken != null) {
      Field f = inferField(DataTypes.ARRAY_ITEM_NAME, typeToken, seenTypeSet);
      return DataTypes.arrayField(name, f);
    } else {
      return inferField("", typeToken, seenTypeSet);
    }
  }

  /**
   * When type is both iterable and bean, we take it as iterable in row-format. Note circular
   * references in bean class is not allowed.
   *
   * @return DataType of a typeToken
   */
  private static Field inferField(
      String name, TypeToken<?> typeToken, LinkedHashSet<Class<?>> seenTypeSet) {
    Class<?> rawType = getRawType(typeToken);
    if (rawType == boolean.class) {
      return field(name, DataTypes.notNullFieldType(ArrowType.Bool.INSTANCE));
    } else if (rawType == byte.class) {
      return field(name, DataTypes.notNullFieldType(new ArrowType.Int(8, true)));
    } else if (rawType == short.class) {
      return field(name, DataTypes.notNullFieldType(new ArrowType.Int(16, true)));
    } else if (rawType == int.class) {
      return field(name, DataTypes.notNullFieldType(new ArrowType.Int(32, true)));
    } else if (rawType == long.class) {
      return field(name, DataTypes.notNullFieldType(new ArrowType.Int(64, true)));
    } else if (rawType == float.class) {
      return field(
          name,
          DataTypes.notNullFieldType(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)));
    } else if (rawType == double.class) {
      return field(
          name,
          DataTypes.notNullFieldType(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)));
    } else if (rawType == Boolean.class) {
      return field(name, FieldType.nullable((ArrowType.Bool.INSTANCE)));
    } else if (rawType == Byte.class) {
      return field(name, FieldType.nullable((new ArrowType.Int(8, true))));
    } else if (rawType == Short.class) {
      return field(name, FieldType.nullable((new ArrowType.Int(16, true))));
    } else if (rawType == Integer.class) {
      return field(name, FieldType.nullable((new ArrowType.Int(32, true))));
    } else if (rawType == Long.class) {
      return field(name, FieldType.nullable((new ArrowType.Int(64, true))));
    } else if (rawType == Float.class) {
      return field(
          name, FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)));
    } else if (rawType == Double.class) {
      return field(
          name, FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)));
    } else if (rawType == java.math.BigDecimal.class) {
      return field(
          name,
          FieldType.nullable(
              new ArrowType.Decimal(DecimalUtils.MAX_PRECISION, DecimalUtils.MAX_SCALE)));
    } else if (rawType == java.math.BigInteger.class) {
      return field(name, FieldType.nullable(new ArrowType.Decimal(DecimalUtils.MAX_PRECISION, 0)));
    } else if (rawType == java.time.LocalDate.class) {
      return field(name, FieldType.nullable(new ArrowType.Date(DateUnit.DAY)));
    } else if (rawType == java.sql.Date.class) {
      return field(name, FieldType.nullable(new ArrowType.Date(DateUnit.DAY)));
    } else if (rawType == java.sql.Timestamp.class) {
      return field(name, FieldType.nullable(new ArrowType.Timestamp(TimeUnit.MICROSECOND, null)));
    } else if (rawType == java.time.Instant.class) {
      return field(name, FieldType.nullable(new ArrowType.Timestamp(TimeUnit.MICROSECOND, null)));
    } else if (rawType == String.class) {
      return field(name, FieldType.nullable(ArrowType.Utf8.INSTANCE));
    } else if (rawType.isEnum()) {
      return field(name, FieldType.nullable(ArrowType.Utf8.INSTANCE));
    } else if (rawType.isArray()) { // array
      Field f =
          inferField(
              DataTypes.ARRAY_ITEM_NAME,
              Objects.requireNonNull(typeToken.getComponentType()),
              seenTypeSet);
      return DataTypes.arrayField(name, f);
    } else if (TypeUtils.ITERABLE_TYPE.isSupertypeOf(typeToken)) { // iterable
      // when type is both iterable and bean, we take it as iterable in row-format
      Field f =
          inferField(DataTypes.ARRAY_ITEM_NAME, TypeUtils.getElementType(typeToken), seenTypeSet);
      return DataTypes.arrayField(name, f);
    } else if (TypeUtils.MAP_TYPE.isSupertypeOf(typeToken)) {
      Tuple2<TypeToken<?>, TypeToken<?>> kvType = TypeUtils.getMapKeyValueType(typeToken);
      Field keyField = inferField(MapVector.KEY_NAME, kvType.f0, seenTypeSet);
      // Map's keys must be non-nullable
      FieldType keyFieldType =
          new FieldType(
              false, keyField.getType(), keyField.getDictionary(), keyField.getMetadata());
      keyField = DataTypes.field(keyField.getName(), keyFieldType, keyField.getChildren());
      Field valueField = inferField(MapVector.VALUE_NAME, kvType.f1, seenTypeSet);
      return DataTypes.mapField(name, keyField, valueField);
    } else if (TypeUtils.isBean(rawType)) { // bean field
      if (seenTypeSet.contains(rawType)) {
        String msg =
            String.format(
                "circular references in bean class is not allowed, but got " + "%s in %s",
                rawType, seenTypeSet);
        throw new UnsupportedOperationException(msg);
      }
      List<Field> fields =
          Descriptor.getDescriptors(rawType).stream()
              .map(
                  descriptor -> {
                    LinkedHashSet<Class<?>> newSeenTypeSet = new LinkedHashSet<>(seenTypeSet);
                    newSeenTypeSet.add(rawType);
                    String n =
                        CaseFormat.LOWER_CAMEL.to(
                            CaseFormat.LOWER_UNDERSCORE, descriptor.getName());
                    return inferField(n, descriptor.getTypeToken(), newSeenTypeSet);
                  })
              .collect(Collectors.toList());
      return DataTypes.structField(name, true, fields);
    } else {
      throw new UnsupportedOperationException(
          String.format(
              "Unsupported type %s for field %s, seen type set is %s",
              typeToken, name, seenTypeSet));
    }
  }

  public static String inferTypeName(TypeToken<?> token) {
    StringBuilder sb = new StringBuilder();
    TypeToken<?> arrayToken = token;
    while (TypeUtils.ITERABLE_TYPE.isSupertypeOf(arrayToken)
        || TypeUtils.MAP_TYPE.isSupertypeOf(arrayToken)) {
      if (TypeUtils.ITERABLE_TYPE.isSupertypeOf(arrayToken)) {
        sb.append(getRawType(arrayToken).getSimpleName());
        arrayToken = TypeUtils.getElementType(arrayToken);
      } else {
        Tuple2<TypeToken<?>, TypeToken<?>> tuple2 = TypeUtils.getMapKeyValueType(arrayToken);
        sb.append("Map");

        if (!TypeUtils.isBean(tuple2.f0)) {
          arrayToken = tuple2.f0;
        }

        if (!TypeUtils.isBean(tuple2.f1)) {
          arrayToken = tuple2.f1;
        }
      }
    }
    return sb.toString();
  }
}
