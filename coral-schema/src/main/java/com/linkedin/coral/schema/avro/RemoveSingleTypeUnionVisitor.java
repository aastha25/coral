/**
 * Copyright 2023 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.schema.avro;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.Lists;
import com.linkedin.avroutil1.compatibility.AvroCompatibilityHelper;

import org.apache.avro.Schema;


public class RemoveSingleTypeUnionVisitor extends AvroSchemaVisitor<Schema> {
  public static Schema visit(Schema schema) {
    return AvroSchemaVisitor.visit(schema, new RemoveSingleTypeUnionVisitor());
  }

  @Override
  public Schema record(Schema record, List<String> names, List<Schema> fields) {
    Schema updatedSchema =
        Schema.createRecord(record.getName(), record.getDoc(), record.getNamespace(), record.isError());

    List<Schema.Field> updatedFields = Lists.newArrayListWithExpectedSize(fields.size());
    for (int i = 0; i < fields.size(); i++) {
      Schema.Field field = record.getFields().get(i);

      final Object defaultValue = SchemaUtilities.defaultValue(field);
      Schema.Field updatedField = AvroCompatibilityHelper.createSchemaField(field.name(), fields.get(i), field.doc(),
          defaultValue, field.order());
      updatedFields.add(updatedField);
    }

    updatedSchema.setFields(updatedFields);
    SchemaUtilities.replicateSchemaProps(record, updatedSchema);

    return updatedSchema;
  }

  @Override
  public Schema union(Schema union, List<Schema> options) {
    if (options.size() > 1) {
      List<Schema> unionOptions = new ArrayList<>(options);
      return Schema.createUnion(unionOptions);
    }
    return options.get(0);
  }

  @Override
  public Schema array(Schema array, Schema element) {
    return Schema.createArray(element);
  }

  @Override
  public Schema map(Schema map, Schema value) {
    return Schema.createMap(value);
  }

  @Override
  public Schema primitive(Schema primitive) {
    return primitive;
  }
}
