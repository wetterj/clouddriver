/*
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.cats.sql.cache

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.cats.agent.AgentDataType.Authority.AUTHORITATIVE
import com.netflix.spinnaker.cats.agent.CachingAgent
import com.netflix.spinnaker.cats.agent.RunnableAgent
import com.netflix.spinnaker.cats.provider.ProviderRegistry
import com.netflix.spinnaker.clouddriver.cache.CustomScheduledAgent
import com.netflix.spinnaker.clouddriver.core.provider.CoreProvider
import com.netflix.spinnaker.config.ConnectionPools
import com.netflix.spinnaker.kork.sql.routing.withPool
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.table
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.TimeUnit

/**
 * Intermittently scans the entire database looking for records created by caching agents that
 * are no longer configured.
 */
class SqlCacheCleanupAgent(
  private val providerRegistry: ProviderRegistry,
  private val jooq: DSLContext,
  private val registry: Registry,
  private val sqlNames: SqlNames
) : RunnableAgent, CustomScheduledAgent {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  private val deletedId = registry.createId("sql.cacheCleanupAgent.dataTypeRecordsDeleted")
  private val timingId = registry.createId("sql.cacheCleanupAgent.dataTypeCleanupDuration")

  override fun run() {
    log.info("Scanning for cache records to cleanup")

    val (agentTypes, agentDataTypes) = findAgentDataTypes()
    val runState = RunState(agentTypes)

    val numDataTypes = agentDataTypes.size
    log.info("Found {} cache data types generated from {} agent types", numDataTypes, agentTypes.size)

    var failures = 0
    withPool(ConnectionPools.CACHE_WRITER.value) {
      agentDataTypes.forEachIndexed { i, dataType ->
        log.info("Scanning '$dataType' (${i + 1}/$numDataTypes) cache records to cleanup")
        try {
          registry.timer(timingId.withTag("dataType", dataType)).record {
            cleanTable(CacheTable.RELATIONSHIP, dataType, runState)
            cleanTable(CacheTable.RESOURCE, dataType, runState)
          }
        } catch (e: SQLException) {
          log.error("Failed to cleanup '$dataType'", e)
          failures++
        }
      }
    }

    log.info("Finished cleanup ($failures failures)")
  }

  /**
   * If the table for [dataType] has not been touched yet, scan through each record it contains,
   * deleting all records that do not correlate to a currently configured agent.
   */
  private fun cleanTable(cacheTable: CacheTable, dataType: String, state: RunState) {
    val tableName = cacheTable.getName(sqlNames, dataType)

    if (state.touchedTables.contains(tableName)) {
      // Nothing to do here, we've already processed this table.
      return
    }
    log.debug("Cleaning table '$tableName' for '$dataType'")

    val rs = jooq.select(*cacheTable.fields)
      .from(table(tableName))
      .fetch()
      .intoResultSet()

    val cleanedAgentTypes = mutableSetOf<String>()
    val idsToClean = mutableListOf<String>()
    while (rs.next()) {
      val agentType = rs.getString(2)
      if (!state.agentTypes.contains(agentType)) {
        idsToClean.add(rs.getString(1))
        cleanedAgentTypes.add(agentType)
      }
    }

    if (idsToClean.isNotEmpty()) {
      log.info(
        "Found ${idsToClean.size} records to cleanup from '$tableName' for data type '$dataType'. " +
          "Reason: Data generated by unknown caching agents ($cleanedAgentTypes})"
      )
      idsToClean.chunked(100) { chunk ->
        jooq.deleteFrom(table(tableName))
          .where("${cacheTable.idColumn()} in (${chunk.joinToString(",") { "'$it'" }})")
          .execute()
      }
    }

    state.touchedTables.add(tableName)

    registry
      .counter(deletedId.withTags("dataType", dataType, "table", cacheTable.name))
      .increment(idsToClean.size.toLong())
  }

  /**
   * Returns a set of all known caching agent names and another set of all known authoritative
   * data types from those caching agents.
   *
   * Agent names will be used to identify what records in the database are no longer attached
   * to existing caching agents, whereas the data types themselves are needed to create the
   * SQL table names, as the tables are derived from the data types, not the agents.
   */
  private fun findAgentDataTypes(): Pair<Set<String>, Set<String>> {
    val agents = providerRegistry.providers
      .flatMap { it.agents }
      .filterIsInstance<CachingAgent>()

    val dataTypes = agents
      .flatMap { it.providedDataTypes }
      .filter { it.authority == AUTHORITATIVE }
      .map { it.typeName }
      .toSet()

    return Pair(agents.map { it.agentType }.toSet(), dataTypes)
  }

  /**
   * Contains per-run state of this cleanup agent.
   */
  private data class RunState(
    val agentTypes: Set<String>,
    val touchedTables: MutableList<String> = mutableListOf()
  )

  /**
   * Abstracts the logical differences--as far as this agent is concerned--between the two
   * varieties of cache tables: The table names and the associated fields we need to read
   * from the database.
   */
  private enum class CacheTable(val fields: Array<Field<*>>) {
    RESOURCE(arrayOf(field("id"), field("agent"))),
    RELATIONSHIP(arrayOf(field("uuid"), field("rel_agent")));

    fun idColumn(): String =
      when (this) {
        RESOURCE -> "id"
        RELATIONSHIP -> "uuid"
      }

    fun getName(sqlNames: SqlNames, dataType: String): String =
      when (this) {
        RESOURCE -> sqlNames.resourceTableName(dataType)
        RELATIONSHIP -> sqlNames.relTableName(dataType)
      }
  }

  override fun getProviderName(): String = CoreProvider.PROVIDER_NAME
  override fun getPollIntervalMillis(): Long = DEFAULT_POLL_INTERVAL
  override fun getTimeoutMillis(): Long = DEFAULT_TIMEOUT
  override fun getAgentType(): String = javaClass.simpleName

  companion object {
    private val DEFAULT_POLL_INTERVAL = TimeUnit.MINUTES.toMillis(2)
    private val DEFAULT_TIMEOUT = TimeUnit.MINUTES.toMillis(1)
  }
}
