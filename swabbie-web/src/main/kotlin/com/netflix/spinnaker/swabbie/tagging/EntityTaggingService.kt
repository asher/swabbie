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

package com.netflix.spinnaker.swabbie.tagging

import com.netflix.spinnaker.swabbie.TagRequest
import com.netflix.spinnaker.swabbie.TaggingService
import com.netflix.spinnaker.swabbie.orca.OrcaJob
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.OrchestrationRequest
import org.springframework.stereotype.Component

@Component
class EntityTaggingService(
  private val orcaService: OrcaService
) : TaggingService {
  override fun removeTag(tagRequest: TagRequest) {
    if (tagRequest is DeleteEntityTagsRequest) {
      orcaService.orchestrate(
        OrchestrationRequest(
          application = tagRequest.application,
          description = tagRequest.description,
          job = listOf(
            OrcaJob(
              type = tagRequest.type,
              context = mutableMapOf(
                "tags" to listOf(tagRequest.id)
              )
            )
          )
        )
      )
    }
  }

  override fun tag(tagRequest: TagRequest) {
    if (tagRequest is UpsertEntityTagsRequest) {
      orcaService.orchestrate(
        OrchestrationRequest(
          application = tagRequest.application,
          description = tagRequest.description,
          job = listOf(
            OrcaJob(
              type = tagRequest.type,
              context = mutableMapOf(
                "tags" to tagRequest.tags,
                "entityRef" to tagRequest.entityRef
              )
            )
          )
        )
      )
    }
  }
}
