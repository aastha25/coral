/**
 * Copyright 2023 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.spark;

import java.util.HashSet;
import java.util.Set;

import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.util.SqlShuttle;


public class RenameTableShuttle extends SqlShuttle {
  private final Set<String> baseTables;

  public RenameTableShuttle(HashSet<String> baseTables) {
    this.baseTables = baseTables;
  }

  @Override
  public SqlNode visit(SqlIdentifier id) {
    if (baseTables.contains(id.toString())) {
      String currentTableName = id.names.get(id.names.size() - 1);
      String newTableName = currentTableName + "_dma_stop_recursion";
      return id.setName(id.names.size() - 1, newTableName);
    }
    return id;
  }
}
