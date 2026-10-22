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
package com.alibaba.cloud.ai.studio.core.model.llm;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import okhttp3.ConnectionPool;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import com.volcengine.ark.runtime.service.ArkService;
import java.time.Duration;
import com.alibaba.cloud.ai.studio.runtime.domain.app.FileSearchOptions;
import com.alibaba.cloud.ai.studio.runtime.domain.knowledgebase.IndexConfig;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.alibaba.cloud.ai.studio.core.base.manager.ProviderManager;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ModelConfigInfo;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ModelCredential;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ProviderConfigInfo;
import com.alibaba.cloud.ai.studio.core.model.embedding.EmbeddingModelDimension;
import com.alibaba.cloud.ai.studio.core.model.reranker.dashscope.DashScopeRerankerOptions;
import com.alibaba.cloud.ai.studio.core.model.reranker.dashscope.DashscopeReranker;
import com.alibaba.cloud.ai.studio.core.utils.ErrorHandlerUtils;
import com.alibaba.cloud.ai.studio.core.utils.api.ApiUtils;

import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static com.alibaba.cloud.ai.studio.core.rag.RagConstants.DEFAULT_DIMENSION;

/**
 * Factory class for creating various AI model instances including chat, embedding, and
 * document ranking models.
 *
 * @since 1.0.0.3
 */

@Slf4j
@Component
public class ModelFactory {

	/** Map of available AI model providers */
	@Resource
	private Map<String, ModelProvider> providerMap;

	/** Manager for handling provider configurations */
	@Resource
	private ProviderManager providerManager;

	@Resource
	private ModelProviderConfig modelProviderConfig;

	@Resource
	private ToolCallingManager toolCallingManager;

	@Resource
	private ObservationRegistry observationRegistry;

	@Resource
	private ChatModelObservationConvention customChatModelObservationConvention;


    @Resource
    RestClient.Builder deepSeekRestClientBuilder;

    @Resource
    WebClient.Builder deepSeekWebClientBuilder;

    @Resource
    RestClient.Builder dashScopeRestClientBuilder;

    @Resource
    WebClient.Builder dashScopeWebClientBuilder;


    private final ConnectionPool arkConnectionPool = new ConnectionPool(20, 1, TimeUnit.MINUTES);


	/**
	 * Creates and returns a chat model instance for the specified provider
	 * @param provider The provider name
	 * @return ChatModel instance
	 */
	public ChatModel getChatModel(String provider) {
        String providerCode = StringUtils.defaultIfBlank(provider, "OpenAI");
        ModelCredential credential;
        String type;
        try {
            ProviderConfigInfo detail = providerManager.getProviderDetail(providerCode, false);
            type = ModelProviderConfig.normalize(detail.getProtocol());
            credential = modelProviderConfig.resolve(type, detail.getCredential());
        }
        catch (BizException error) {
            // Only canonical vendor codes may fall back to server-side YAML; never arbitrary record IDs.
            if (!ModelProviderConfig.PROVIDERS.contains(providerCode.toLowerCase(java.util.Locale.ROOT))
                    || !ErrorCode.INVALID_PARAMS.getCode().equals(error.getError().getCode())) {
                throw error;
            }
            type = ModelProviderConfig.normalize(providerCode);
            credential = modelProviderConfig.resolve(type, null);
        }
        if (StringUtils.isBlank(credential.getApiKey())) {
            throw new BizException(ErrorCode.INVALID_PARAMS.toError("apikey", "api key is invalid."));
        }

        if (!"openai".equals(type)) {
            return createNativeChatModel(type, credential);
        }
        OpenAiApi openAiApi = buildOpenAiApi(credential);
		OpenAiChatModel openAiChatModel = OpenAiChatModel.builder()
				.openAiApi(openAiApi)
				.observationRegistry(observationRegistry)
				.build();
		openAiChatModel.setObservationConvention(customChatModelObservationConvention);
		return openAiChatModel;
	}

	/**
	 * Creates and returns an embedding model instance with specified configuration
	 * @param metadataMode The metadata mode for the embedding model
	 * @param indexConfig The index configuration containing model details
	 * @return EmbeddingModel instance
	 */
	public EmbeddingModel getEmbeddingModel(MetadataMode metadataMode, IndexConfig indexConfig) {
		ModelCredential credential = getModelCredential(indexConfig.getEmbeddingProvider(),
				indexConfig.getEmbeddingModel());

		int dimension = EmbeddingModelDimension.getDimension(indexConfig.getEmbeddingModel(), DEFAULT_DIMENSION);

		OpenAiApi openAiApi = buildOpenAiApi(credential);
		return new OpenAiEmbeddingModel(openAiApi, metadataMode,
				OpenAiEmbeddingOptions.builder().model(indexConfig.getEmbeddingModel()).dimensions(dimension).build());
	}

	/**
	 * Creates and returns a document ranker instance for search operations
	 * @param searchOptions The search configuration options
	 * @return DocumentRanker instance
	 */
	public DashscopeReranker getDocumentRanker(FileSearchOptions searchOptions) {
		ModelCredential credential = getModelCredential(searchOptions.getRerankProvider(),
				searchOptions.getRerankModel());

		return DashscopeReranker.builder()
			.dashscopeApi(DashScopeApi.builder().apiKey(credential.getApiKey()).build())
			.options(DashScopeRerankerOptions.builder()
				.returnDocuments(false)
				.topN(searchOptions.getTopK())
				.model(searchOptions.getRerankModel())
				.build())
			.build();
	}

	/**
	 * Generates a cache key for model instances
	 * @param modelConfig The model configuration
	 * @return Cache key string
	 */
	private String getModelInstanceKey(ModelConfigInfo modelConfig) {
		return modelConfig.getProvider() + ":" + modelConfig.getModelId();
	}

	/**
	 * Retrieves model credentials for the specified provider and model
	 * @param provider The provider name
	 * @param modelId The model identifier
	 * @return ModelCredential instance
	 */
	private ModelCredential getModelCredential(String provider, String modelId) {
		ProviderConfigInfo providerDetail = providerManager.getProviderDetail(provider, false);
		ModelCredential credential = providerDetail.getCredential();

		if (StringUtils.isBlank(credential.getApiKey())) {
			throw new BizException(ErrorCode.INVALID_PARAMS.toError("apikey", "api key is invalid."));
		}

		return credential;
	}

	/**
	 * Builds an OpenAI API instance with the provided credentials
	 * @param credential The model credentials
	 * @return OpenAiApi instance
	 */
	private OpenAiApi buildOpenAiApi(ModelCredential credential) {
		OpenAiApi.Builder openAiApiBuilder = OpenAiApi.builder()
				.apiKey(credential.getApiKey())
				.responseErrorHandler(ErrorHandlerUtils.OPENAI_RESPONSE_ERROR_HANDLER)
				.headers(ApiUtils.getBaseHeaders());

        if (StringUtils.isNotBlank(credential.getEndpoint())) {
            ModelCredential routed = ModelProviderConfig.route(credential);
            openAiApiBuilder.baseUrl(routed.getEndpoint())
                    .completionsPath(routed.getCompletionsPath())
                    .embeddingsPath(routed.getEmbeddingsPath());
        }

		return openAiApiBuilder.build();
	}


    public ChatModel createNativeChatModel(String provider, ModelCredential credential) {
        String type = ModelProviderConfig.normalize(provider);
        ChatModel model;
        switch (type) {
            case "dashscope" -> {
                DashScopeChatModel nativeModel = DashScopeChatModel.builder()
                        .dashScopeApi(DashScopeApi.builder().apiKey(credential.getApiKey()).restClientBuilder(dashScopeRestClientBuilder)
                                .webClientBuilder(dashScopeWebClientBuilder)
                                .baseUrl(dashScopeBaseUrl(credential.getEndpoint())).build())
                        .toolCallingManager(toolCallingManager).observationRegistry(observationRegistry).build();
                nativeModel.setObservationConvention(customChatModelObservationConvention);
                model = nativeModel;
            }
            case "deepseek" -> {
                DeepSeekChatModel nativeModel = DeepSeekChatModel.builder()
                        .deepSeekApi(DeepSeekApi.builder().apiKey(credential.getApiKey())
                                .baseUrl(credential.getEndpoint())
                                .webClientBuilder(deepSeekWebClientBuilder)
                                .restClientBuilder(deepSeekRestClientBuilder)
                                .completionsPath(credential.getCompletionsPath()).build())
                        .toolCallingManager(toolCallingManager).observationRegistry(observationRegistry).build();
                nativeModel.setObservationConvention(customChatModelObservationConvention);
                model = nativeModel;
            }
            case "doubao" -> model = new ArkChatModel(() -> ArkService.builder().connectionPool(arkConnectionPool)
                    .apiKey(credential.getApiKey()).baseUrl(credential.getEndpoint())
                    .timeout(Duration.ofMinutes(15)).build(), toolCallingManager, observationRegistry, customChatModelObservationConvention);
            default -> throw new IllegalArgumentException("Not a native vendor: " + provider);
        }
        return new NativeOptionsChatModel(type, model);
    }

    /** Checks the provider's authenticated model-catalog endpoint without invoking a model. */
    public void testProviderConnection(String provider, ModelCredential credential) {
        String type = ModelProviderConfig.normalize(provider);
        try {
            String endpoint = StringUtils.removeEnd(credential.getEndpoint().trim(), "/");
            endpoint = endpoint.replaceFirst("/chat/completions$", "");
            if ("doubao".equals(type)) {
                testArkConnection(endpoint, credential.getApiKey());
                return;
            }
            if ("dashscope".equals(type)) {
                endpoint = endpoint.replaceFirst("/compatible-mode(?:/v1)?$", "");
            }
            String path = switch (type) {
                case "deepseek" -> "/models";
                case "dashscope" -> (endpoint.endsWith("/api/v1") ? "" : "/api/v1") + "/models";
                default -> (endpoint.endsWith("/v1") ? "" : "/v1") + "/models";
            };
            checkApi(endpoint + path, credential.getApiKey());
        }
        catch (RestClientResponseException e) {
            String reason = switch (e.getStatusCode().value()) {
                case 401 -> "API-KEY 无效，请核对后重新输入";
                case 403 -> "API-KEY 无权访问该服务，请检查账号权限";
                case 404 -> "API URL 不正确，请检查服务商地址";
                default -> "API-KEY 或 API URL 验证失败，请检查后重试";
            };
            throw providerCredentialError(reason);
        }
        catch (RestClientException | IllegalArgumentException e) {
            throw providerCredentialError("连接供应商失败，请检查 API URL 和网络后重试");
        }
    }

    /** Loads model IDs from the provider's authenticated model catalog. */
    public List<Map<String, Object>> listProviderModels(String provider, ModelCredential credential) {
        String type = ModelProviderConfig.normalize(provider);
        if ("doubao".equals(type)) {
            throw providerCredentialError("火山方舟查询账号接入点需要 AccessKey/SecretKey；当前配置只有 API-KEY，请在方舟控制台查看接入点 ID");
        }
        String endpoint = StringUtils.removeEnd(credential.getEndpoint().trim(), "/");
        endpoint = endpoint.replaceFirst("/chat/completions$", "");
        String path;
        if ("deepseek".equals(type)) {
            endpoint = endpoint.replaceFirst("/v1$", "");
            path = "/models";
        }
        else if ("dashscope".equals(type)) {
            endpoint = endpoint.replaceFirst("/compatible-mode(?:/v1)?$", "");
            path = (endpoint.endsWith("/api/v1") ? "" : "/api/v1") + "/models";
        }
        else {
            path = (endpoint.endsWith("/v1") ? "" : "/v1") + "/models";
        }
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(5));
            requestFactory.setReadTimeout(Duration.ofSeconds(10));
            RestClient client = RestClient.builder().requestFactory(requestFactory).build();
            List<Map<String, Object>> models = new ArrayList<>();
            if ("dashscope".equals(type)) {
                for (int page = 1; page <= 20; page++) {
                    Map<?, ?> response = client.get().uri(URI.create(endpoint + path + "?page_no=" + page + "&page_size=100"))
                            .header("Authorization", "Bearer " + credential.getApiKey()).retrieve().body(Map.class);
                    List<Map<String, Object>> currentPage = extractProviderModels(response);
                    models.addAll(currentPage);
                    if (currentPage.size() < 100) break;
                }
            }
            else {
                Map<?, ?> response = client.get().uri(URI.create(endpoint + path))
                        .header("Authorization", "Bearer " + credential.getApiKey()).retrieve().body(Map.class);
                models.addAll(extractProviderModels(response));
            }
            if (models.isEmpty()) throw providerCredentialError("供应商没有返回可用模型列表");
            return models.stream().collect(java.util.stream.Collectors.toMap(entry -> entry.get("model_id"), entry -> entry,
                    (first, ignored) -> first, java.util.LinkedHashMap::new)).values().stream().toList();
        }
        catch (RestClientResponseException e) {
            throw providerCredentialError(e.getStatusCode().value() == 401
                    ? "API-KEY 无效，请核对后重新输入" : "获取模型列表失败，请检查 API URL 和账号权限");
        }
        catch (RestClientException | IllegalArgumentException e) {
            throw providerCredentialError("获取模型列表失败，请检查 API URL 和网络后重试");
        }
    }

    private List<Map<String, Object>> extractProviderModels(Object value) {
        if (!(value instanceof Map<?, ?> map)) return List.of();
        for (String key : List.of("data", "models")) {
            Object items = map.get(key);
            if (items instanceof List<?> list) {
                List<Map<String, Object>> models = new ArrayList<>();
                for (Object item : list) {
                    String id = item instanceof String valueId ? valueId : modelId(item);
                    if (StringUtils.isBlank(id)) continue;
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("model_id", id);
                    entry.put("tags", inferModelTags(id, item));
                    models.add(entry);
                }
                if (!models.isEmpty()) return models.stream()
                        .collect(java.util.stream.Collectors.toMap(entry -> entry.get("model_id"), entry -> entry,
                                (first, ignored) -> first, java.util.LinkedHashMap::new))
                        .values().stream().toList();
            }
            if (items instanceof Map<?, ?> nested) {
                List<Map<String, Object>> models = extractProviderModels(nested);
                if (!models.isEmpty()) return models;
            }
        }
        Object output = map.get("output");
        return output instanceof Map<?, ?> nested ? extractProviderModels(nested) : List.of();
    }

    private String modelId(Object value) {
        if (!(value instanceof Map<?, ?> model)) return null;
        for (String key : List.of("id", "model", "model_name")) {
            Object id = model.get(key);
            if (id != null && !id.toString().isBlank()) return id.toString();
        }
        return null;
    }

    private List<String> inferModelTags(String id, Object value) {
        String name = id.toLowerCase(java.util.Locale.ROOT);
        List<String> tags = new ArrayList<>();
        Map<?, ?> model = value instanceof Map<?, ?> item ? item : Map.of();
        String capabilities = String.valueOf(model.get("capabilities")).toLowerCase(java.util.Locale.ROOT);
        String features = String.valueOf(model.get("features")).toLowerCase(java.util.Locale.ROOT);
        String apiCapabilities = String.valueOf(model.get("api_capabilities")).toLowerCase(java.util.Locale.ROOT);
        String modalities = String.valueOf(model.get("input_modalities")).toLowerCase(java.util.Locale.ROOT)
                + String.valueOf(model.get("inference_metadata")).toLowerCase(java.util.Locale.ROOT);
        if (capabilities.contains("vu") || modalities.contains("image") || name.matches(".*(vision|[-_]vl([-_].*)?|gpt-4o|gemini).*$")) tags.add("vision");
        if (features.contains("web-search") || name.contains("search")) tags.add("web_search");
        if (capabilities.contains("tr") || capabilities.contains("me") || name.contains("embed")) tags.add("embedding");
        if (capabilities.contains("reasoning") || model.containsKey("effort")
                || name.matches(".*(reason|[-_]r1([-_].*)?|qwq|thinking|[-_]o[1-9]([-_].*)?).*")) tags.add("reasoning");
        if (features.contains("function-calling") || apiCapabilities.contains("function")
                || (!tags.contains("embedding") && !name.matches(".*(image|tts|whisper|rerank).*$"))) tags.add("function_call");
        return tags;
    }

    private BizException providerCredentialError(String reason) {
        return new BizException(ErrorCode.INVALID_PARAMS.toError("credential_config", reason));
    }

    private void checkApi(String url, String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        RestClient.builder().requestFactory(requestFactory).build().get().uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .retrieve().toBodilessEntity();
    }

    /** Ark has no model-list API; an omitted model is rejected before inference, after auth. */
    private void testArkConnection(String endpoint, String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        RestClient.builder().requestFactory(requestFactory).build().post()
                .uri(URI.create(endpoint + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("messages", List.of(Map.of("role", "user", "content", "ping"))))
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)
                            .toLowerCase(java.util.Locale.ROOT);
                    boolean missingModel = status == 400 && body.contains("model")
                            && (body.contains("required") || body.contains("missing") || body.contains("empty"));
                    if ((status >= 200 && status < 300) || missingModel) return null;
                    String reason = status == 401 ? "API-KEY 无效，请核对后重新输入"
                            : status == 404 ? "API URL 不正确，请检查服务商地址"
                            : "API-KEY 或 API URL 验证失败，请检查后重试";
                    throw providerCredentialError(reason);
                });
    }

    /** Migrate the previously saved compatible-mode URL to the native DashScope root. */
    public static String dashScopeBaseUrl(String endpoint) {
        String value = endpoint.replaceAll("/+$", "");
        return value.replaceFirst("/compatible-mode(?:/v1)?$", "");
    }
}
