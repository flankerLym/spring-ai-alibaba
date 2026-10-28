/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.studio.core.model.llm.impl;

import org.springframework.stereotype.Component;

/**
 * Provider implementation for Doubao.
 *
 * Reuses the common provider defaults. Runtime Ark endpoint/model handling
 * remains in the existing model execution adapter.
 */
@Component("DoubaoProvider")
public class DoubaoProvider extends OpenAIProvider {

    @Override
    public String getCode() {
        return "Doubao";
    }

    @Override
    public String getName() {
        return "豆包";
    }

    @Override
    public String getDescription() {
        return "Volcengine Ark / Doubao model service";
    }

    @Override
    public String getProtocol() {
        return "Doubao";
    }
}
