/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package com.netflix.spinnaker.swabbie

import com.netflix.spectator.api.LongTaskTimer
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.swabbie.events.DeleteResourceEvent
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.events.UnMarkResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.work.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.LocalDate
import java.time.Period

abstract class AbstractResourceHandler<out T : Resource>(
  private val registry: Registry,
  private val clock: Clock,
  private val rules: List<Rule<T>>,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val exclusionPolicies: List<ResourceExclusionPolicy>,
  private val ownerResolver: OwnerResolver,
  private val applicationEventPublisher: ApplicationEventPublisher
) : ResourceHandler<T> {
  protected val log: Logger = LoggerFactory.getLogger(javaClass)
  private val markDurationTimer: LongTaskTimer = registry.longTaskTimer("swabbie.mark.duration")
  private val candidatesCountId = registry.createId("swabbie.resources.candidatesCount")

  /**
   * finds & tracks cleanup candidates
   */
  override fun mark(workConfiguration: WorkConfiguration, postMark: () -> Unit) {
    val timerId = markDurationTimer.start()
    try {
      log.info("${javaClass.name}: getting resources with namespace {}", workConfiguration.namespace)
      getUpstreamResources(workConfiguration)?.let { upstreamResources ->
        log.info("fetched {} resources with namespace {}, dryRun {}", upstreamResources.size, workConfiguration.namespace, workConfiguration.dryRun)
        upstreamResources.filter {
          !it.shouldBeExcluded(exclusionPolicies, workConfiguration.exclusions)
        }.forEach { upstreamResource ->
            rules.mapNotNull {
              it.apply(upstreamResource).summary
            }.let { violationSummaries ->
                resourceTrackingRepository.find(
                  resourceId = upstreamResource.resourceId,
                  namespace = workConfiguration.namespace
                ).let { trackedMarkedResource ->
                  if (trackedMarkedResource != null && violationSummaries.isEmpty() && !workConfiguration.dryRun) {
                    log.info("Forgetting now valid resource {}", upstreamResource)
                    resourceTrackingRepository.remove(trackedMarkedResource)
                    applicationEventPublisher.publishEvent(UnMarkResourceEvent(trackedMarkedResource, workConfiguration))
                  } else if (!violationSummaries.isEmpty() && trackedMarkedResource == null) {
                    MarkedResource(
                      resource = upstreamResource,
                      summaries = violationSummaries,
                      namespace = workConfiguration.namespace,
                      resourceOwner = ownerResolver.resolve(upstreamResource),
                      projectedDeletionStamp = workConfiguration.retentionDays.days.fromNow.atStartOfDay(clock.zone).toInstant().toEpochMilli()
                    ).let {
                      registry.counter(
                        candidatesCountId.withTags(
                          "resourceType", workConfiguration.resourceType,
                          "configuration", workConfiguration.namespace
                        )
                      ).increment()
                      if (!workConfiguration.dryRun) {
                        resourceTrackingRepository.upsert(it)
                        log.info("Marking resource {} for deletion", it)
                        applicationEventPublisher.publishEvent(MarkResourceEvent(it, workConfiguration))
                      }
                    }
                  }
                }
              }
          }
      }
    } catch (e: Exception) {
      log.error("Failed while invoking $javaClass", e)
    } finally {
      postMark.invoke()
      markDurationTimer.stop(timerId)
    }
  }

  /**
   * deletes violating resources
   */
  override fun clean(markedResource: MarkedResource, workConfiguration: WorkConfiguration, postClean: () -> Unit) {
    try {
      getUpstreamResource(markedResource, workConfiguration)
        .let { resource ->
          if (resource == null) {
            log.info("Resource {} no longer exists", markedResource)
            resourceTrackingRepository.remove(markedResource)
          } else {
            resource
              .takeIf { !it.shouldBeExcluded(exclusionPolicies, workConfiguration.exclusions) }
              ?.let { upstreamResource ->
                rules.mapNotNull {
                  it.apply(upstreamResource).summary
                }.let { violationSummaries ->
                    if (violationSummaries.isEmpty() && !workConfiguration.dryRun) {
                      applicationEventPublisher.publishEvent(UnMarkResourceEvent(markedResource, workConfiguration))
                      resourceTrackingRepository.remove(markedResource)
                    } else {
                      // adjustedDeletionStamp is the adjusted projectedDeletionStamp after notification is sent
                      log.info("Preparing deletion of {}. dryRun {}", markedResource, workConfiguration.dryRun)
                      if (markedResource.adjustedDeletionStamp != null && !workConfiguration.dryRun) {
                        remove(markedResource, workConfiguration)
                        resourceTrackingRepository.remove(markedResource)
                        applicationEventPublisher.publishEvent(DeleteResourceEvent(markedResource, workConfiguration))
                      }
                    }
                  }
              }
          }
        }
    } catch (e: Exception) {
      log.error("failed to cleanup resource", markedResource, e)
    } finally {
      postClean.invoke()
    }
  }

  private val Int.days: Period
    get() = Period.ofDays(this)

  private val Period.fromNow: LocalDate
    get() = LocalDate.now(clock) + this

  abstract fun remove(markedResource: MarkedResource, workConfiguration: WorkConfiguration)
}
