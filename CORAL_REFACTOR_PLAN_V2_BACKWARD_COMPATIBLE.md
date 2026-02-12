# Coral Refactoring Plan V2: Backward Compatible Migration to Plugin Architecture

**Focus:** Maintain backward compatibility while enabling clean plugin-based architecture

---

## Part 1: Current State Analysis 

### 1.1 Actual Class Names 

**In `coral-common/src/main/java/com/linkedin/coral/common/catalog/`:**
- ✅ `CoralCatalog.java` - Interface for multi-format catalogs
- ✅ `CoralTable.java` - Interface for multi-format tables
- ✅ `HiveTable.java` - Implementation of CoralTable for Hive (wraps `org.apache.hadoop.hive.metastore.api.Table`)
- ✅ `IcebergTable.java` - Implementation of CoralTable for Iceberg (wraps `org.apache.iceberg.Table`)
- ✅ `TableType.java` - Enum (TABLE, VIEW)

### 1.2 Current Converter Hierarchy

```
ToRelConverter (abstract, in coral-common)
  ├─ Constructors:
  │  ├─ ToRelConverter(HiveMetastoreClient)  // Legacy, Hive-only
  │  ├─ ToRelConverter(CoralCatalog)         // Modern, multi-format
  │  └─ ToRelConverter(localMetaStore)       // Testing
  │
  ├─ Methods:
  │  ├─ convertSql(String sql) → RelNode
  │  ├─ convertView(String db, String view) → RelNode
  │  └─ processView(String db, String table) → SqlNode
  │      ├─ processViewWithCoralCatalog() // Handles HiveTable & IcebergTable
  │      └─ processViewWithMsc()          // Handles Hive only
  │
  └─ Subclasses:
     ├─ HiveToRelConverter (in coral-hive)
     │  └─ Parses HiveQL using ParseTreeBuilder
     │
     └─ TrinoToRelConverter (in coral-trino)
        └─ Parses Trino SQL using TrinoParserDriver
```

### 1.3 User-Facing APIs (Current)

#### **Option 1: Legacy Hive-only (Deprecated but must remain working)**
```java
HiveMetastoreClient msc = ...;
HiveToRelConverter converter = new HiveToRelConverter(msc);
RelNode rel = converter.convertView("db", "hive_view");
```

#### **Option 2: Modern Multi-format (Current)**
```java
CoralCatalog catalog = new CoralCatalogClientImpl();  // Can return HiveTable or IcebergTable
HiveToRelConverter converter = new HiveToRelConverter(catalog);
RelNode rel = converter.convertView("db", "view_backed_by_hive_and_iceberg");
```
---

## Part 2: Problems & Requirements

### 2.1 Problems Identified

1. **Dependency Pollution**
   - coral-common has both Hive + Iceberg deps
   - All downstream (coral-hive, coral-trino) inherits both
   - Users forced to get both even if only need one

2. **Format-Specific Code in Wrong Place**
   - `HiveCalciteTableAdapter`, `HiveToCoralTypeConverter` in coral-common
   - `IcebergCalciteTableAdapter`, `IcebergToCoralTypeConverter` in coral-common
   - Should be in format-specific modules

3. **Backward Compatibility Required**
   - Users with `HiveToRelConverter(HiveMetastoreClient)` must continue working
   - Same package, same class name, same behavior
   - No forced migration

4. **No User-Facing API for Multi-Format**
   - Need clear API: "I have a CoralCatalog with mixed tables, give me RelNode"

### 2.2 Requirements

#### **Must Have (Non-negotiable):**
1. ✅ **Backward Compatibility**
   - `HiveToRelConverter(HiveMetastoreClient)` stays in `com.linkedin.coral.hive.hive2rel`
   - Same behavior, no breaking changes
   - Can be marked `@Deprecated` but must work

2. ✅ **Clean Dependency Separation**
   - Hive format code → separate module (compileOnly or runtimeOnly)
   - Iceberg format code → separate module (compileOnly or runtimeOnly)
   - Users choose format plugins at runtime

3. ✅ **Plugin Architecture**
   - SPI-based format plugins
   - Easy to add new formats 
   - Format-agnostic core

#### **Should Have:**
4. ✅ **Clear User-Facing API for CoralCatalog**
   - Single entry point for "CoralCatalog with mixed tables → RelNode"
   - Name reflects multi-format support
   - Works with HiveQL, Trino SQL, etc.

5. ✅ **Consistent Converter Support**
   - All converters (HiveToRelConverter, TrinoToRelConverter) support CoralCatalog
   - Consistent constructor patterns

---

## Part 3: Proposed Solution

### 3.1 Module Structure 

```
coral/
├── coral-api/                          # Pure interfaces, no format deps
│   ├── catalog/
│   │   ├── CoralCatalog.java
│   │   ├── CoralTable.java
│   │   └── TableType.java
│   ├── types/                          # Unified type system
│   │   ├── CoralDataType.java
│   │   └── [all type classes]
│   ├── spi/                            # NEW: Plugin SPI
│   │   ├── FormatPlugin.java
│   │   └── FormatPluginRegistry.java
│   └── functions/                      # Generic function interfaces
│       ├── FunctionRegistry.java
│       └── Function.java
│   Dependencies: calcite-core
│
├── coral-calcite/                      # Format-agnostic Calcite utilities
│   ├── ToRelConverter.java             # Internal base class (multi-format orchestrator)
│   ├── CoralRootSchema.java            # Multi-format schema (uses SPI)
│   ├── CoralDatabaseSchema.java        # Multi-format DB schema (uses SPI)
│   ├── CoralJavaTypeFactoryImpl.java   # Calcite type factory
│   ├── HiveTypeSystem.java             # MISNAMED! Calcite type config (→ rename CoralTypeSystem)
│   ├── HiveRelBuilder.java             # MISNAMED! RelBuilder with UNNEST (→ rename CoralRelBuilder)
│   ├── HiveUncollect.java              # MISNAMED! Custom Uncollect node (→ rename CoralUncollect)
│   ├── FuzzyUnionSqlRewriter.java      # Format-agnostic UNION fixer
│   ├── TypeConverter.java              # Hive TypeInfo ↔ Calcite (evaluate if needed)
│   ├── calcite/CalciteUtil.java
│   ├── utils/
│   │   ├── TypeDerivationUtil.java
│   │   └── RelDataTypeToHiveTypeStringConverter.java
│   ├── transformers/                   # Format-agnostic SQL transformers
│   │   └── [SqlCallTransformer, etc.]
│   └── bridge/                         # Temporary cross-format bridges
│       └── IcebergHiveTableConverter.java  # MOVED from catalog/ (temporary, issue #575)
│   Dependencies: coral-api
│                 compileOnly hive-metastore (for bridge)
│                 compileOnly iceberg-hive-metastore (for bridge)
│
├── coral-format-hive/                  # NEW: Hive format plugin
│   ├── HiveMetastoreClient.java        # MOVED from coral-common
│   ├── HiveMscAdapter.java             # MOVED from coral-common
│   ├── HiveSchema.java                 # MOVED from coral-common (legacy Calcite adapter)
│   ├── HiveDbSchema.java               # MOVED from coral-common (legacy Calcite adapter)
│   ├── HiveCalciteTableAdapter.java    # MOVED from coral-common
│   ├── HiveCalciteViewAdapter.java     # MOVED from coral-common
│   ├── HiveToCoralTypeConverter.java   # MOVED from coral-common
│   ├── catalog/HiveTable.java          # MOVED from coral-common
│   ├── testing/                        # MOVED from coral-common
│   │   ├── LocalMetastoreHiveSchema.java
│   │   ├── LocalMetastoreHiveDbSchema.java
│   │   └── LocalMetastoreHiveTable.java
│   └── spi/HiveFormatPlugin.java       # NEW: SPI implementation
│   Dependencies: coral-api
│                 compileOnly hive-metastore
│                 compileOnly hadoop-common
│
├── coral-format-iceberg/               # NEW: Iceberg format plugin
│   ├── IcebergCalciteTableAdapter.java # MOVED from coral-common
│   ├── IcebergToCoralTypeConverter.java # MOVED from coral-common
│   ├── catalog/IcebergTable.java       # MOVED from coral-common
│   └── spi/IcebergFormatPlugin.java    # NEW: SPI implementation
│   Dependencies: coral-api
│                 compileOnly iceberg-api
│                 compileOnly iceberg-core
│                 compileOnly iceberg-hive-metastore
│
├── coral-hive/                         # HiveQL parser + Hive→Rel
│   ├── HiveToRelConverter.java         # STAYS HERE (backward compat)
│   │   └─ Constructor(HiveMetastoreClient) // Legacy @Deprecated, still works
│   │   └─ Constructor(CoralCatalog)        // Modern, uses plugins via SPI
│   ├── parsetree/ParseTreeBuilder.java
│   ├── functions/HiveFunctionResolver.java
│   └── [HiveQL ANTLR parser]
│   Dependencies: coral-api, coral-calcite
│
├── coral-trino/                        # Rel→Trino + Trino→Rel
│   ├── RelToTrinoConverter.java
│   ├── TrinoToRelConverter.java        # STAYS HERE
│   │   └─ Constructor(HiveMetastoreClient) // Legacy @Deprecated
│   │   └─ Constructor(CoralCatalog)        // Already exists
│   └── [Trino transformers]
│   Dependencies: coral-api, coral-calcite, coral-hive
│
├── coral-spark/                        # Rel→Spark
│   └── [Spark transformers]
│   Dependencies: coral-api, coral-calcite, coral-hive
│
└── coral-common/                       # NEW: Facade for backward compatibility
    └── Re-exports all classes in original packages
    Dependencies: coral-api, coral-calcite, coral-format-hive, coral-format-iceberg

Note: Classes with "Hive" prefix in coral-calcite are MISNAMED - they're actually
      format-agnostic Calcite utilities. Can be renamed to "Coral" prefix for clarity.
```

**Key Corrections:**
1. ✅ `HiveTypeSystem`, `HiveRelBuilder`, `HiveUncollect` → `coral-calcite` (not coral-format-hive!)
2. ✅ `FuzzyUnionSqlRewriter`, `TypeConverter` → `coral-calcite` (format-agnostic)
3. ✅ `IcebergHiveTableConverter` → `coral-calcite/bridge/` (cross-format, temporary)
4. ✅ `compileOnly` strategy for all format dependencies

### 3.2 SPI Plugin Interface 

#### **coral-api/spi/FormatPlugin.java**
```java
package com.linkedin.coral.common.spi;

import org.apache.calcite.schema.Table as CalciteTable;
import com.linkedin.coral.common.catalog.CoralTable;
import com.linkedin.coral.common.types.CoralDataType;

/**
 * Service Provider Interface for table format plugins.
 * Plugins provide format-specific adapters and type converters.
 */
public interface FormatPlugin {
  
  /**
   * Returns the format name this plugin handles (e.g., "hive", "iceberg").
   */
  String getFormatName();
  
  /**
   * Checks if this plugin can handle the given CoralTable.
   * 
   * @param coralTable The table to check
   * @return true if this plugin can create an adapter for this table
   */
  boolean supports(CoralTable coralTable);
  
  /**
   * Creates a Calcite table adapter for the given CoralTable.
   * Returns null if this plugin doesn't support the table.
   * 
   * @param coralTable The Coral table to adapt
   * @param schemaPath The schema path (for views)
   * @return Calcite Table adapter, or null if not supported
   */
  CalciteTable createTableAdapter(CoralTable coralTable, List<String> schemaPath);
}
```

#### **coral-format-hive/spi/HiveFormatPlugin.java**
```java
package com.linkedin.coral.format.hive.spi;

import com.linkedin.coral.common.spi.FormatPlugin;
import com.linkedin.coral.common.catalog.CoralTable;
import com.linkedin.coral.common.catalog.HiveTable;  // CORRECTED: HiveTable, not HiveCoralTable
import com.linkedin.coral.common.catalog.TableType;
import com.linkedin.coral.format.hive.HiveCalciteTableAdapter;
import com.linkedin.coral.format.hive.HiveCalciteViewAdapter;

/**
 * Format plugin for Hive tables.
 * Provides Calcite adapters for HiveTable instances.
 */
public class HiveFormatPlugin implements FormatPlugin {
  
  @Override
  public String getFormatName() {
    return "hive";
  }
  
  @Override
  public boolean supports(CoralTable coralTable) {
    return coralTable instanceof HiveTable;
  }
  
  @Override
  public org.apache.calcite.schema.Table createTableAdapter(
      CoralTable coralTable, List<String> schemaPath) {
    
    if (!(coralTable instanceof HiveTable)) {
      return null;  // Not a Hive table
    }
    
    HiveTable hiveTable = (HiveTable) coralTable;
    
    // Dispatch based on table type
    if (hiveTable.tableType() == TableType.VIEW) {
      return new HiveCalciteViewAdapter(hiveTable, schemaPath);
    } else {
      return new HiveCalciteTableAdapter(hiveTable);
    }
  }
}
```

**Plugin Registration (3 Options):**

**Option 1: Java SPI (Standard, Recommended)**
```
coral-format-hive/src/main/resources/META-INF/services/com.linkedin.coral.common.spi.FormatPlugin
  ↳ com.linkedin.coral.format.hive.spi.HiveFormatPlugin
```

File contents (plain text, one class per line):
```
com.linkedin.coral.format.hive.spi.HiveFormatPlugin
```

**Pros:** Standard Java mechanism, zero custom code, works with ServiceLoader  
**Cons:** Longer path name

---

**Option 2: Properties File (Custom Loader)**

If you prefer simpler paths, you can use a properties file:
```
coral-format-hive/src/main/resources/coral-plugins.properties
  ↳ com.linkedin.coral.common.spi.FormatPlugin=com.linkedin.coral.format.hive.spi.HiveFormatPlugin
```

**Requires custom loader in FormatPluginRegistry:**
```java
// coral-api/spi/FormatPluginRegistry.java (CUSTOM APPROACH)

private static List<FormatPlugin> discoverPlugins() {
  List<FormatPlugin> plugins = new ArrayList<>();
  
  // Load from properties files on classpath
  try {
    Enumeration<URL> resources = 
      FormatPluginRegistry.class.getClassLoader()
        .getResources("coral-plugins.properties");
    
    while (resources.hasMoreElements()) {
      Properties props = new Properties();
      props.load(resources.nextElement().openStream());
      
      String pluginClass = props.getProperty("com.linkedin.coral.common.spi.FormatPlugin");
      if (pluginClass != null) {
        FormatPlugin plugin = (FormatPlugin) Class.forName(pluginClass).newInstance();
        plugins.add(plugin);
      }
    }
  } catch (Exception e) {
    throw new RuntimeException("Failed to load plugins", e);
  }
  
  return plugins;
}
```

**Pros:** Simpler file path  
**Cons:** Custom loader code, not standard Java SPI

---

**Option 3: Programmatic Registration (No Files)**

Register plugins in code:
```java
// User code or static initializer
FormatPluginRegistry.register(new HiveFormatPlugin());
FormatPluginRegistry.register(new IcebergFormatPlugin());
```

**Pros:** No configuration files, explicit control  
**Cons:** Not automatic, requires code changes to add plugins

---

**Recommendation:** Use **Option 1 (Java SPI)** because:
- ✅ Industry standard (used by JDBC drivers, logging frameworks, etc.)
- ✅ Zero custom code required
- ✅ Automatic discovery
- ✅ Well-tested by Java ecosystem
- ✅ IDEs understand it (auto-completion, validation)
- ⚠️ Path is long but you only create it once

The `META-INF/services/` path is a Java convention that's been around since JDK 1.3. It's verbose but standard.

#### **coral-format-iceberg/spi/IcebergFormatPlugin.java**
```java
package com.linkedin.coral.format.iceberg.spi;

import com.linkedin.coral.common.spi.FormatPlugin;
import com.linkedin.coral.common.catalog.CoralTable;
import com.linkedin.coral.common.catalog.IcebergTable;  // CORRECTED: IcebergTable, not IcebergCoralTable
import com.linkedin.coral.format.iceberg.IcebergCalciteTableAdapter;

/**
 * Format plugin for Iceberg tables.
 * Provides Calcite adapters for IcebergTable instances.
 */
public class IcebergFormatPlugin implements FormatPlugin {
  
  @Override
  public String getFormatName() {
    return "iceberg";
  }
  
  @Override
  public boolean supports(CoralTable coralTable) {
    return coralTable instanceof IcebergTable;
  }
  
  @Override
  public org.apache.calcite.schema.Table createTableAdapter(
      CoralTable coralTable, List<String> schemaPath) {
    
    if (!(coralTable instanceof IcebergTable)) {
      return null;  // Not an Iceberg table
    }
    
    IcebergTable icebergTable = (IcebergTable) coralTable;
    return new IcebergCalciteTableAdapter(icebergTable);
  }
}
```

**Register via META-INF/services:**
```
coral-format-iceberg/src/main/resources/META-INF/services/com.linkedin.coral.common.spi.FormatPlugin
  ↳ com.linkedin.coral.format.iceberg.spi.IcebergFormatPlugin
```

File contents:
```
com.linkedin.coral.format.iceberg.spi.IcebergFormatPlugin
```

### 3.3 Multi-Format Schema (Uses SPI)

#### **coral-calcite/CoralDatabaseSchema.java** (Updated)
```java
package com.linkedin.coral.common;

import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Table as CalciteTable;
import com.linkedin.coral.common.catalog.CoralCatalog;
import com.linkedin.coral.common.catalog.CoralTable;
import com.linkedin.coral.common.spi.FormatPlugin;
import com.linkedin.coral.common.spi.FormatPluginRegistry;

/**
 * Multi-format Calcite schema for a database.
 * Uses SPI to discover format plugins and dispatch table access.
 */
public class CoralDatabaseSchema implements Schema {
  
  private final CoralCatalog coralCatalog;
  private final String dbName;
  private final List<String> schemaPath;
  
  public CoralDatabaseSchema(CoralCatalog coralCatalog, String dbName) {
    this.coralCatalog = coralCatalog;
    this.dbName = dbName;
    this.schemaPath = ImmutableList.of(CoralRootSchema.ROOT_SCHEMA, dbName);
  }
  
  @Override
  public CalciteTable getTable(String tableName) {
    // Get table from multi-format catalog
    CoralTable coralTable = coralCatalog.getTable(dbName, tableName);
    if (coralTable == null) {
      return null;
    }
    
    // Try each plugin until one handles this table
    Collection<FormatPlugin> plugins = FormatPluginRegistry.getAllPlugins();
    
    if (plugins.isEmpty()) {
      throw new IllegalStateException(
        "No format plugins found! Add coral-format-hive or coral-format-iceberg to classpath.");
    }
    
    for (FormatPlugin plugin : plugins) {
      if (plugin.supports(coralTable)) {
        CalciteTable table = plugin.createTableAdapter(coralTable, schemaPath);
        if (table != null) {
          return table;  // Found the right plugin!
        }
      }
    }
    
    throw new IllegalStateException(
      "No plugin found to handle table: " + dbName + "." + tableName + 
      " (type: " + coralTable.getClass().getSimpleName() + ")");
  }
  
  // ... other Schema methods
}
```

**Key Point:** `CoralDatabaseSchema` is **multi-format**. It asks each plugin "can you handle this HiveTable/IcebergTable?" until one says yes. This allows **mixing Hive and Iceberg tables in the same catalog**!

---

## Part 4: Backward Compatibility Strategy

### 4.1 Keep Existing APIs Working

#### **Option 1: HiveMetastoreClient (Legacy, stays working)**
```java
// coral-hive/HiveToRelConverter.java (NO CHANGES to this constructor)

@Deprecated  // Mark deprecated but keep working
public HiveToRelConverter(HiveMetastoreClient msc) {
  super(msc);  // Calls ToRelConverter(HiveMetastoreClient)
  this.parseTreeBuilder = new ParseTreeBuilder(functionResolver);
}

// How it works:
// - ToRelConverter(HiveMetastoreClient) creates HiveSchema
// - HiveSchema uses HiveMetastoreClient directly (no plugins)
// - HiveCalciteTableAdapter, HiveToCoralTypeConverter must stay accessible
// - Works exactly as before, no behavior changes
```

**Backward Compatibility Requirements:**
- ✅ `HiveToRelConverter(HiveMetastoreClient)` stays in `coral-hive`
- ✅ `HiveMetastoreClient` interface stays accessible
- ✅ `HiveSchema`, `HiveDbSchema` stay accessible (marked `@Deprecated`)
- ✅ `HiveCalciteTableAdapter`, `HiveCalciteViewAdapter` stay accessible
- ✅ All stay in same packages (can move internally but must be re-exported)

#### **Option 2: CoralCatalog (Modern, uses plugins)**
```java
// coral-hive/HiveToRelConverter.java

public HiveToRelConverter(CoralCatalog catalog) {
  super(catalog);  // Calls ToRelConverter(CoralCatalog)
  this.parseTreeBuilder = new ParseTreeBuilder(functionResolver);
}

// How it works:
// - ToRelConverter(CoralCatalog) creates CoralRootSchema
// - CoralRootSchema creates CoralDatabaseSchema
// - CoralDatabaseSchema uses SPI to find format plugins
// - Plugins provide HiveCalciteTableAdapter or IcebergCalciteTableAdapter
// - Works with mixed Hive + Iceberg tables!
```

### 4.2 Future Enhancements

Additional improvements (optional, not required for core refactoring):

- Add CoralCatalog constructor to TrinoToRelConverter (if not already present)
- Create clean SqlToRelConverter facade API in coral-api
- Rename misnamed classes (HiveTypeSystem → CoralTypeSystem, etc.)
- Deprecate TypeConverter in favor of two-stage conversion
- Support additional SQL dialects (Spark, Presto, etc.)

See `CORAL_REFACTOR_FUTURE_ENHANCEMENTS.md` for detailed specifications

---

## Part 5: Migration Plan

### Phase 1: Create New Modules (Weeks 1-2)

#### Step 1.1: Create coral-api Module
```gradle
// coral-api/build.gradle
dependencies {
  compile calcite-core
  // NO format dependencies!
}
```

**Move to coral-api:**
- `catalog/CoralCatalog.java`
- `catalog/CoralTable.java`
- `catalog/TableType.java`
- `types/*` (all type system classes)
- `functions/FunctionRegistry.java`, `Function.java` (generic interfaces)
- **NEW:** `spi/FormatPlugin.java`
- **NEW:** `spi/FormatPluginRegistry.java`
- **NEW (optional):** `converter/SqlToRelConverter.java`

#### Step 1.2: Create coral-format-hive Module
```gradle
// coral-format-hive/build.gradle
dependencies {
  compile project(':coral-api')
  compile hive-metastore
  compile hadoop-common
}
```

**Move to coral-format-hive:**
- `HiveMetastoreClient.java` (from coral-common) - Interface for Hive metastore
- `HiveMscAdapter.java` (from coral-common) - Wraps IMetaStoreClient
- `HiveSchema.java` (from coral-common) - Calcite schema using HiveMetastoreClient
- `HiveDbSchema.java` (from coral-common) - Calcite DB schema using HiveMetastoreClient
- `HiveCalciteTableAdapter.java` (from coral-common) - Hive table → Calcite (extracts Dali UDF metadata)
- `HiveCalciteViewAdapter.java` (from coral-common) - Hive view → Calcite
- `HiveToCoralTypeConverter.java` (from coral-common) - Hive TypeInfo → Coral type
- `catalog/HiveTable.java` (from coral-common) - CoralTable impl for Hive
- `LocalMetastoreHiveSchema.java` (from coral-common) - Testing utilities
- `LocalMetastoreHiveDbSchema.java` (from coral-common) - Testing utilities  
- `LocalMetastoreHiveTable.java` (from coral-common) - Testing utilities
- **NEW:** `spi/HiveFormatPlugin.java`
- **NEW:** `META-INF/services/com.linkedin.coral.common.spi.FormatPlugin`

#### Step 1.3: Create coral-format-iceberg Module
```gradle
// coral-format-iceberg/build.gradle
dependencies {
  compile project(':coral-api')
  compile iceberg-api
  compile iceberg-core
  compile iceberg-hive-metastore
}
```

**Move to coral-format-iceberg:**
- `IcebergCalciteTableAdapter.java` (from coral-common)
- `IcebergToCoralTypeConverter.java` (from coral-common)
- `catalog/IcebergTable.java` (from coral-common)
- `catalog/IcebergHiveTableConverter.java` (temporary, from coral-common)
- **NEW:** `spi/IcebergFormatPlugin.java`
- **NEW:** `META-INF/services/com.linkedin.coral.common.spi.FormatPlugin`

#### Step 1.4: Rename coral-common → coral-calcite
```gradle
// coral-calcite/build.gradle
dependencies {
  compile project(':coral-api')
  // NO format dependencies at compile time!
}
```

**What stays in coral-calcite:**
- `ToRelConverter.java` (internal base class) - Multi-format orchestrator
- `CoralRootSchema.java`, `CoralDatabaseSchema.java` (use SPI) - Multi-format schemas
- `CoralJavaTypeFactoryImpl.java` - Calcite type factory with struct field handling
- `HiveTypeSystem.java` - **MISNAMED!** Just Calcite RelDataTypeSystem config (can rename to CoralTypeSystem)
- `HiveRelBuilder.java` - **MISNAMED!** RelBuilder with custom UNNEST handling (can rename to CoralRelBuilder)
- `HiveUncollect.java` - **MISNAMED!** Custom Calcite Uncollect node (can rename to CoralUncollect)
- `FuzzyUnionSqlRewriter.java` - Format-agnostic UNION schema mismatch fixer
- `TypeConverter.java` - Hive TypeInfo ↔ Calcite RelDataType (keep for now, or deprecate)
- `calcite/CalciteUtil.java` - Calcite utilities
- `utils/` (TypeDerivationUtil, RelDataTypeToHiveTypeStringConverter) - Calcite utilities
- `transformers/` (SqlCallTransformer utilities) - Format-agnostic SQL transformers
- `bridge/IcebergHiveTableConverter.java` - **NEW location** (temporary cross-format bridge)

**Note on "Hive" Named Classes:**
Many classes named "Hive*" are actually format-agnostic Calcite utilities:
- `HiveTypeSystem` → Configures Calcite type precision/scale (not Hive-specific)
- `HiveRelBuilder` → Handles UNNEST semantics for all Coral converters (not Hive-only)
- `HiveUncollect` → Custom UNNEST node used by Hive/Trino/Spark converters (not Hive-only)
- `FuzzyUnionSqlRewriter` → Fixes UNION schema mismatches (format-agnostic)

These can be renamed to `Coral*` to reflect their true purpose, but functionality-wise they belong in coral-calcite.

#### Step 1.5: Provide Backward Compatibility Facade (Optional)

Create `coral-common` as a facade module for smooth migration:

```gradle
// coral-common/build.gradle (NEW: facade module)
dependencies {
  compile project(':coral-api')
  compile project(':coral-calcite')
  compile project(':coral-format-hive')
  compile project(':coral-format-iceberg')
  
  // Re-export everything for backward compatibility
}
```

**Re-export classes in original packages:**
```java
// coral-common/src/main/java/com/linkedin/coral/common/HiveMetastoreClient.java
package com.linkedin.coral.common;

/**
 * @deprecated Moved to coral-format-hive. This class is re-exported for backward compatibility.
 * Use {@link com.linkedin.coral.format.hive.HiveMetastoreClient} directly.
 */
@Deprecated
public interface HiveMetastoreClient extends com.linkedin.coral.format.hive.HiveMetastoreClient {
  // Empty - just re-exports the interface
}
```

**This allows users to:**
1. Keep using `compile 'coral-common'` (gets everything, like before)
2. Or migrate to `compile 'coral-api' + runtimeOnly 'coral-format-hive'` (clean approach)

### Phase 2: Update Converters (Weeks 3-4)

#### Step 2.1: Update coral-hive Dependencies
```gradle
// coral-hive/build.gradle (UPDATED)
dependencies {
  compile deps.antlr
  compile deps.antlr-runtime
  compile deps.ivy
  
  compile project(':coral-api')
  compile project(':coral-calcite')
  
  // Format support is optional at compile time
  // Plugins loaded via SPI at runtime
  compileOnly project(':coral-format-hive')
}
```

#### Step 2.2: Update HiveToRelConverter

**No changes needed to constructors!** But add javadoc clarifications:

```java
// coral-hive/hive2rel/HiveToRelConverter.java

/**
 * Constructor for backward compatibility with HiveMetastoreClient.
 * 
 * <p><b>Legacy API:</b> This constructor uses HiveMetastoreClient directly
 * and only supports Hive tables. For multi-format support (Hive + Iceberg),
 * use {@link #HiveToRelConverter(CoralCatalog)} instead.
 * 
 * @param hiveMetastoreClient Hive metastore client
 * @deprecated Use {@link #HiveToRelConverter(CoralCatalog)} for multi-format support
 */
@Deprecated
public HiveToRelConverter(HiveMetastoreClient hiveMetastoreClient) {
  super(hiveMetastoreClient);
  this.parseTreeBuilder = new ParseTreeBuilder(functionResolver);
}

/**
 * Constructor accepting CoralCatalog for unified multi-format catalog access.
 * 
 * <p>This constructor uses SPI to discover format plugins at runtime.
 * Supported formats depend on which plugins are in the classpath:
 * <ul>
 *   <li>coral-format-hive: Enables Hive table support</li>
 *   <li>coral-format-iceberg: Enables Iceberg table support</li>
 * </ul>
 * 
 * <p>The catalog can contain a mix of Hive and Iceberg tables. The appropriate
 * format plugin will be selected automatically based on the table type.
 * 
 * @param catalog Coral catalog providing access to tables across formats
 */
public HiveToRelConverter(CoralCatalog catalog) {
  super(catalog);
  this.parseTreeBuilder = new ParseTreeBuilder(functionResolver);
}
```

### Phase 3: Update Downstream Modules (Weeks 5-6)

#### Step 3.1: Update coral-trino Dependencies
```gradle
// coral-trino/build.gradle (UPDATED)
dependencies {
  compile deps.gson
  compile project(':coral-api')
  compile project(':coral-calcite')
  compile project(':coral-hive')
  compile project(':shading:coral-trino-parser', configuration: 'shadow')
  
  // NO format dependencies!
  // Users add format plugins they need
}
```

#### Step 3.2: Update coral-spark Dependencies
```gradle
// coral-spark/build.gradle (UPDATED)
dependencies {
  compile project(':coral-api')
  compile project(':coral-calcite')
  compile project(':coral-hive')
  compile project(':coral-schema')
  compileOnly deps.spark.sql
  
  // NO format dependencies!
}
```

### Phase 4: Documentation & Migration Guide (Week 7)

#### Create Migration Guide for Users

**For users with existing code:**

```markdown
# Coral Migration Guide

## Option 1: Keep Everything Working (No Changes)

Keep using `coral-common` as a facade:

```gradle
dependencies {
  compile 'com.linkedin.coral:coral-hive:x.y.z'
  compile 'com.linkedin.coral:coral-common:x.y.z'  // Facade includes everything
}
```

Your existing code works unchanged:
```java
HiveMetastoreClient msc = ...;
HiveToRelConverter converter = new HiveToRelConverter(msc);
```

## Option 2: Migrate to Plugin Architecture (Recommended)

Choose format plugins you need:

```gradle
dependencies {
  compile 'com.linkedin.coral:coral-hive:x.y.z'
  
  // Choose format support:
  runtimeOnly 'com.linkedin.coral:coral-format-hive:x.y.z'     // For Hive
  runtimeOnly 'com.linkedin.coral:coral-format-iceberg:x.y.z'  // For Iceberg
}
```

Update code to use CoralCatalog:
```java
CoralCatalog catalog = new CoralCatalogClient();
HiveToRelConverter converter = new HiveToRelConverter(catalog);
```

Benefits:
- ✅ Smaller classpath
- ✅ Only include formats you need
- ✅ Support for mixed Hive + Iceberg tables
```

---

## Part 6: Final Architecture Diagrams

### 6.1 Dependency Graph (After Refactor)

```
                    ┌──────────────┐
                    │  coral-api   │
                    │ (interfaces) │
                    │   + SPI      │
                    └──────┬───────┘
                           │
        ┌──────────────────┼──────────────────┬────────────────┐
        │                  │                  │                │
        ↓                  ↓                  ↓                ↓
┌───────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ coral-format- │  │coral-format- │  │   coral-     │  │   coral-     │
│     hive      │  │   iceberg    │  │  calcite     │  │    hive      │
│ (Hive impl)   │  │(Iceberg impl)│  │(multi-format │  │ (HiveQL      │
│ + SPI plugin  │  │+ SPI plugin  │  │ uses SPI)    │  │  parser)     │
└───────────────┘  └──────────────┘  └──────┬───────┘  └──────┬───────┘
        │                  │                 │                 │
        │ compileOnly      │ compileOnly     │ compile         │ compile
        └──────────────────┴─────────────────┴─────────────────┘
                                     │
                                     ↓
                            ┌──────────────┐
                            │ coral-trino  │
                            └──────────────┘
                                     ↑
                          User adds format plugins at runtime

┌──────────────┐
│coral-common  │  ← Optional facade for backward compatibility
│  (facade)    │     Re-exports everything
└──────────────┘
```

### 6.2 Runtime Plugin Discovery Flow

```
User Code:
  CoralCatalog catalog = new DaliCoralCatalogClient();
  HiveToRelConverter converter = new HiveToRelConverter(catalog);
  RelNode rel = converter.convertView("db", "view_name");

        ↓
        
HiveToRelConverter(CoralCatalog)
  → super(catalog)
  
        ↓
        
ToRelConverter(CoralCatalog)
  → Creates CoralRootSchema(catalog)
  
        ↓
        
CoralRootSchema.getSubSchema("db")
  → Returns CoralDatabaseSchema(catalog, "db")
  
        ↓
        
CoralDatabaseSchema.getTable("view_name")
  → catalog.getTable("db", "view_name")  
  → Returns HiveTable (instance of CoralTable)
  
        ↓
        
  → FormatPluginRegistry.getAllPlugins()
  → For each plugin:
      ├─ IcebergFormatPlugin.supports(HiveTable)? → false
      └─ HiveFormatPlugin.supports(HiveTable)? → true!
         └─ HiveFormatPlugin.createTableAdapter(HiveTable, schemaPath)
            → Returns HiveCalciteViewAdapter (Calcite Table)
            
        ↓
        
  → Calcite uses HiveCalciteViewAdapter to access table metadata
  → ParseTreeBuilder parses view definition
  → Result: RelNode
```

**Key Insight:** Even though it's a "HiveToRelConverter", it can handle Iceberg tables because:
1. The catalog returns `IcebergTable` instances
2. SPI discovers `IcebergFormatPlugin`
3. Plugin provides `IcebergCalciteTableAdapter`
4. Calcite works with the adapter

The "Hive" in `HiveToRelConverter` refers to **HiveQL parsing**, not table format!

---

## Part 7: Benefits Summary

### For Users with Existing Code (HiveMetastoreClient):
- ✅ **Zero migration required** - Code works exactly as before
- ✅ **Same packages, same classes** - No import changes
- ✅ **Can migrate gradually** - Use facade module, then move to plugins

### For Users Adopting CoralCatalog:
- ✅ **Clean API** - `SqlToRelConverter(dialect, catalog)`
- ✅ **Multi-format support** - Mix Hive + Iceberg in same catalog
- ✅ **Smaller classpath** - Only include formats you need
- ✅ **Consistent converters** - HiveToRelConverter and TrinoToRelConverter both support CoralCatalog

### For Coral Developers:
- ✅ **Clear separation** - Format code isolated in plugins
- ✅ **Easier testing** - Test formats independently
- ✅ **Extensibility** - Add Delta Lake, Hudi without touching core
- ✅ **Clean dependencies** - No circular deps, proper layering

### For Future (Trino views with mixed tables):
```java
// Trino view backed by Hive + Iceberg tables
CoralCatalog catalog = new DaliCoralCatalogClient();
TrinoToRelConverter converter = new TrinoToRelConverter(catalog);  // NEW!
RelNode rel = converter.convertView("db", "trino_view_over_mixed_tables");
// Works! Plugins handle both Hive and Iceberg tables
```

---

## Part 8: IcebergHiveTableConverter Placement & Version Flexibility

### 8.1 IcebergHiveTableConverter - Where Should It Live?

#### Current State

**Location:** `coral-common/src/main/java/com/linkedin/coral/common/catalog/IcebergHiveTableConverter.java`

**Purpose:** Temporary bridge that converts `IcebergTable` → Hive `Table` object

**Dependencies (IMPORTANT):**
```java
import org.apache.hadoop.hive.metastore.api.Table;           // from hive-metastore
import org.apache.iceberg.hive.HiveSchemaUtil;               // from iceberg-hive-metastore
```

**Note:** Uses `org.apache.iceberg.hive.HiveSchemaUtil` from `iceberg-hive-metastore` module, which itself depends on:
- `iceberg-api`
- `iceberg-core`
- `hive-exec`
- `hive-common`
- `hive-metastore`

So this is a **heavy bridge** with transitive Hive + Iceberg dependencies!

#### Where It Should Live in Plugin Architecture

**❌ Option 1: coral-format-iceberg** (BAD)
- Forces Iceberg plugin to depend on Hive!
- Defeats separation of concerns

**❌ Option 2: coral-format-hive** (BAD)
- Forces Hive plugin to depend on Iceberg!
- Same problem, reversed

**✅ Option 3: coral-calcite (RECOMMENDED)**
```
coral-calcite/
  └─ bridge/IcebergHiveTableConverter.java
     Dependencies: 
       compileOnly hive-metastore
       compileOnly iceberg-hive-metastore  (brings iceberg + hive deps)
```

**Why this works:**
- `ToRelConverter` (in coral-calcite) uses this converter
- It's a **cross-format bridge**, not format-specific
- Both dependencies are `compileOnly` - users provide at runtime
- Only works when both plugins are present
- Clearly marked as temporary/bridge code

**Dependency Strategy:**
```gradle
// coral-calcite/build.gradle
dependencies {
  compile project(':coral-api')
  
  // Bridge dependencies (temporary for issue #575)
  compileOnly 'org.apache.hive:hive-metastore:3.1.2'
  compileOnly 'org.apache.iceberg:iceberg-hive-metastore:1.4.0'  // Heavy! Brings both Hive + Iceberg
}
```

**Runtime Availability Check:**
```java
// ToRelConverter.processViewWithCoralCatalog() (in coral-calcite)

if (coralTable instanceof IcebergTable) {
  IcebergTable icebergTable = (IcebergTable) coralTable;
  
  // Check if bridge classes are available (requires both format plugins)
  try {
    Class.forName("com.linkedin.coral.calcite.bridge.IcebergHiveTableConverter");
    Class.forName("org.apache.iceberg.hive.HiveSchemaUtil");
    
    // Both available - use bridge
    table = IcebergHiveTableConverter.toHiveTable(icebergTable);
  } catch (ClassNotFoundException e) {
    // Bridge not available - gracefully degrade
    // This happens if user only includes coral-format-iceberg without coral-format-hive
    stringViewExpandedText = "SELECT * FROM " + dbName + "." + tableName;
    table = null; // Create minimal placeholder or skip UDF resolution
  }
}
```

**Javadoc Update:**
```java
/**
 * TEMPORARY BRIDGE: Converts IcebergTable to Hive Table for backward compatibility.
 * 
 * <p><b>Location:</b> Lives in coral-calcite (not a format plugin) because it depends
 * on BOTH Hive and Iceberg via {@code iceberg-hive-metastore} module. All dependencies
 * are compileOnly - users must provide both format plugins at runtime.
 * 
 * <p><b>Heavy Dependency:</b> Uses {@code org.apache.iceberg.hive.HiveSchemaUtil} from
 * {@code iceberg-hive-metastore}, which transitively brings:
 * <ul>
 *   <li>iceberg-api, iceberg-core</li>
 *   <li>hive-exec, hive-common, hive-metastore</li>
 * </ul>
 * 
 * <p><b>Runtime Requirement:</b> Only works when BOTH plugins are present:
 * <ul>
 *   <li>coral-format-hive</li>
 *   <li>coral-format-iceberg</li>
 * </ul>
 * 
 * <p><b>Will be removed:</b> Once issue #575 is complete, ParseTreeBuilder will accept
 * CoralTable directly, and this bridge will be deleted.
 * 
 * @deprecated Temporary bridge code for issue #575
 */
@Deprecated
public class IcebergHiveTableConverter { ... }
```

---

### 8.2 Iceberg Version Flexibility - Enable Users to Plugin Any Version

#### The Problem

**Current:** Coral depends on LinkedIn's Iceberg fork
```gradle
compile deps.'linkedin-iceberg'.'iceberg-api'
compile deps.'linkedin-iceberg'.'iceberg-core'
compile deps.'linkedin-iceberg'.'iceberg-hive-metastore'
```

**Goal:** Allow users to provide their own Iceberg version:
- OSS Trino → wants Apache Iceberg 1.x
- LinkedIn internal → wants LinkedIn Iceberg fork
- Other users → want different Iceberg versions

**Challenge:** How to avoid version conflicts while supporting multiple Iceberg versions?

---

#### Solution: Use `compileOnly` for ALL Format Dependencies

**Strategy:**

1. **coral-api**: Pure interfaces, NO format dependencies
2. **coral-format-hive**: Hive as `compileOnly`
3. **coral-format-iceberg**: Iceberg as `compileOnly`
4. **Users provide**: Their own format library versions at runtime

**Implementation:**

```gradle
// coral-format-iceberg/build.gradle
dependencies {
  compile project(':coral-api')
  
  // Iceberg dependencies as compileOnly - NOT packaged!
  compileOnly 'org.apache.iceberg:iceberg-api:1.4.0'
  compileOnly 'org.apache.iceberg:iceberg-core:1.4.0'
  compileOnly 'org.apache.iceberg:iceberg-hive-metastore:1.4.0'
  
  // Test with specific versions
  testImplementation 'org.apache.iceberg:iceberg-api:1.4.0'
  testImplementation 'org.apache.iceberg:iceberg-core:1.4.0'
}
```

**Key Point:** `coral-format-iceberg` compiles against Iceberg 1.4.0 but **does NOT package it**!

---

#### User Experience: OSS Trino with Apache Iceberg 1.6.0

**User's build.gradle:**
```gradle
dependencies {
  // Trino provides its own Iceberg version
  compile 'org.apache.iceberg:iceberg-api:1.6.0'
  compile 'org.apache.iceberg:iceberg-core:1.6.0'
  compile 'org.apache.iceberg:iceberg-hive-metastore:1.6.0'
  
  // Coral with Iceberg plugin (NO Iceberg classes inside!)
  compile 'com.linkedin.coral:coral-hive:2.0.0'
  runtimeOnly 'com.linkedin.coral:coral-format-iceberg:2.0.0'
}
```

**What happens:**
1. ✅ `coral-format-iceberg.jar` contains **NO Iceberg classes** (compileOnly)
2. ✅ Trino's Iceberg 1.6.0 is on classpath
3. ✅ Coral's plugin uses Trino's Iceberg 1.6.0 at runtime
4. ✅ **No version conflicts!**

---

#### Complete Dependency Strategy (All Modules)

```gradle
// coral-api/build.gradle
dependencies {
  compile 'org.apache.calcite:calcite-core'
  // NO format dependencies!
}

// coral-format-hive/build.gradle
dependencies {
  compile project(':coral-api')
  compileOnly 'org.apache.hive:hive-metastore:3.1.2'
  compileOnly 'org.apache.hadoop:hadoop-common:3.3.1'
}

// coral-format-iceberg/build.gradle
dependencies {
  compile project(':coral-api')
  compileOnly 'org.apache.iceberg:iceberg-api:1.4.0'
  compileOnly 'org.apache.iceberg:iceberg-core:1.4.0'
  compileOnly 'org.apache.iceberg:iceberg-hive-metastore:1.4.0'
}

// coral-calcite/build.gradle
dependencies {
  compile project(':coral-api')
  // Bridge dependencies (temporary for issue #575)
  compileOnly 'org.apache.hive:hive-metastore:3.1.2'
  compileOnly 'org.apache.iceberg:iceberg-hive-metastore:1.4.0'
}

// coral-hive/build.gradle
dependencies {
  compile project(':coral-api')
  compile project(':coral-calcite')
  antlr 'org.antlr:antlr4:4.9.3'
  compile 'org.antlr:antlr4-runtime:4.9.3'
  // NO format dependencies!
}

// coral-trino/build.gradle
dependencies {
  compile project(':coral-api')
  compile project(':coral-calcite')
  compile project(':coral-hive')
  // NO format dependencies!
}
```

**Result:** Users control **all** format dependency versions!
---

## Part 9: Open Questions & Decisions

### Q1: Keep coral-common as Facade?
- **Option A:** Keep `coral-common` as facade (includes all formats)
  - ✅ Smooth migration, no breaking changes
  - ❌ Users still get all dependencies unless they explicitly migrate
  
- **Option B:** Remove `coral-common`, force migration
  - ✅ Clean break, forces best practices
  - ❌ Breaking change, requires all users to update

**Recommendation:** Option A for v1.0, Option B for v2.0 (with deprecation cycle)

### Q2: Where does ParseTreeBuilder access Hive Table?
Currently `ParseTreeBuilder` and `HiveFunctionResolver` use `org.apache.hadoop.hive.metastore.api.Table` for Dali UDF resolution.

**Problem:** If coral-hive has `compileOnly coral-format-hive`, how does it access Hive classes?

**Solution:** 
- ParseTreeBuilder already receives `Table` as parameter (line 117: `toSqlNode(sql, Table hiveView)`)
- ToRelConverter converts `IcebergTable → Hive Table` using `IcebergHiveTableConverter` (temporary bridge)
- So ParseTreeBuilder never directly imports Hive classes, just receives them as parameters
- **This already works!** No changes needed.

Future: Issue #575 will refactor ParseTreeBuilder to accept CoralTable directly, eliminating this bridge.
---

## Appendix: Class Relocation Table (Corrected)

**Special Note on IcebergHiveTableConverter:**
- **Why in coral-calcite:** It's a cross-format bridge that depends on BOTH Hive + Iceberg
- **Dependencies (CORRECTED):**
```gradle
// coral-calcite/build.gradle
dependencies {
  compile project(':coral-api')
  
  // Bridge dependencies (temporary for issue #575)
  // IMPORTANT: IcebergHiveTableConverter uses iceberg-hive-metastore which brings BOTH:
  //   - Iceberg deps (iceberg-api, iceberg-core)
  //   - Hive deps (hive-exec, hive-common, hive-metastore)
  compileOnly 'org.apache.hive:hive-metastore:3.1.2'
  compileOnly 'org.apache.iceberg:iceberg-hive-metastore:1.4.0'
}
``` Uses `iceberg-hive-metastore` module (brings both format deps)
- **Temporary:** Will be removed after issue #575
- **Runtime requirement:** Only works when both format plugins are present

---

**End of Refactoring Plan V2**
