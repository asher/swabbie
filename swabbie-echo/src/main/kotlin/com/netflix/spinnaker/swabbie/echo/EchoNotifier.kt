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

package com.netflix.spinnaker.swabbie.echo

import com.netflix.spinnaker.swabbie.notifications.Notifier
import org.springframework.stereotype.Component

@Component
class EchoNotifier(
  private val echoService: EchoService
) : Notifier {
  override fun notify(recipient: String, additionalContext: Map<String, Any>, messageType: String) {
    echoService.create(
      EchoService.Notification(
        notificationType = Notifier.NotificationType.valueOf(messageType),
        to = recipient.split(","),
        severity = Notifier.NotificationSeverity.HIGH,
        source = EchoService.Notification.Source("swabbie"),
        templateGroup = "swabbie",
        additionalContext = additionalContext
      )
    )
  }
}
