/*
 * Copyright 2016 The OpenDCT Authors. All Rights Reserved
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

package opendct.config.messages;

public enum MessageTitle {
    CONSUMER_INIT_FAILURE("Consumer initialization failure."),
    CONSUMER_STALLED("Consumer stalled."),
    PRODUCER_INIT_FAILURE("Producer initialization failure."),
    PRODUCER_STALLED("Producer stalled."),
    DEVICES_LOADED_FAILURE("Required capture devices not loaded.");

    public final String TITLE;

    MessageTitle(String title) {
        TITLE = title;
    }

    @Override
    public String toString() {
        return TITLE;
    }
}
