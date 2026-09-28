# ai-agent-service

Java 21, Spring Boot 4.1.1, Maven. Минимальный REST-клиент существующего OpenAI Managed Agent «Лана — стройматериалы».

## Запуск

Задайте `OPENAI_API_KEY` и `OPENAI_AGENT_ID` в окружении процесса (например, через настройки запуска IDE или secret manager). Ключ должен иметь доступ к проекту OpenAI, где сохранён агент. `OPENAI_AGENT_ID` — ID сохранённого Managed Agent, а не его имя, ID workflow, prompt или старого Assistant.

```sh
mvn spring-boot:run
```

Сервис слушает порт **8082**. После изменения окружения перезапустите процесс.

Первый запрос: `sessionId` можно пропустить или передать `null`.

```sh
curl -X POST http://localhost:8082/api/v1/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"У меня бюджет 30000 рублей","sessionId":null}'
```

Пример ответа (ID и текст здесь иллюстративные):

```json
{"sessionId":"session_...","response":"Что планируете строить?"}
```

Второй запрос: подставьте **реальный `sessionId` из первого ответа**. OpenAI продолжит тот же разговор, сохранив бюджет из первого сообщения.

```sh
curl -X POST http://localhost:8082/api/v1/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Хочу построить забор. Какой бюджет я назвал?","sessionId":"session_..."}'
```

Ответ содержит тот же `sessionId` и новый `response`. ID хранится на клиенте и передаётся в каждом последующем запросе. Чтобы начать новый разговор, снова пропустите `sessionId` или передайте `null`. Пустая строка не считается новой session и возвращает `400`.

```sh
curl http://localhost:8082/actuator/health
```

Health проверяет работоспособность приложения, не выполняет платный запрос OpenAI и не подтверждает доступ к агенту.

## Подключение OpenAI

Контракт проверен по официальной документации 22 сентября 2026:

- [Создание session](https://developers.openai.com/api/reference/ruby/resources/beta/subresources/agents/subresources/sessions/methods/create)
- [Запуск и продолжение session](https://developers.openai.com/api/docs/guides/agents-api/sessions)
- [События и сохранённые результаты](https://developers.openai.com/api/docs/guides/agents-api/sessions/events)
- [Отправка сообщения в session](https://developers.openai.com/api/reference/typescript/resources/beta/subresources/agents/subresources/sessions/subresources/events/methods/create)
- [Получение turns](https://developers.openai.com/api/reference/ruby/resources/beta/subresources/agents/subresources/sessions/subresources/turns/methods/list)
- [Получение items](https://developers.openai.com/api/reference/ruby/resources/beta/subresources/agents/subresources/sessions/subresources/items/methods/list)

`RestClient` вызывает `https://api.openai.com/v1` с Bearer authentication и `OpenAI-Beta: agents=v1`.

1. Без `sessionId`: `POST /agents/sessions` с `agent_id` из окружения, `environment: {"type":"none"}`, `input` и `stream: false`.
2. С `sessionId`: получить session, проверить её принадлежность `OPENAI_AGENT_ID` и состояние `idle`; получить последний turn как исходный курсор; отправить `POST /agents/sessions/{id}/events` с событием `agent.session.input.message`. Ответ `202` без тела означает принятие сообщения, а не завершение работы. Новая session не создаётся, в том числе при ошибке.
3. Опрос turns с эксклюзивным `after` от исходного курсора до завершения **нового** корневого turn. Исторические ответы не принимаются за новый результат. Один `idle` не означает успешное завершение.
4. Чтение страниц items; возврат завершённого ответа assistant только для выбранного turn, без commentary и reasoning, вместе с OpenAI session ID.

Определение агента используется без переопределения модели и инструкций. Модель `gpt-5.6-luna` должна быть настроена у агента в Platform. Сервис не создаёт и не редактирует агентов. Изменения сохранённого определения применяются к новым session; существующая session сохраняет свою конфигурацию.

Сервис stateless: БД и реестра session в памяти приложения нет. История остаётся в OpenAI, ID хранит клиент. Клиент должен отправлять сообщения одной session **последовательно**, дожидаясь ответа на предыдущий запрос. Уже занятая session возвращает `409 SESSION_BUSY`: сообщение в активную session по контракту OpenAI направляет текущий turn, а не создаёт новый. Проверка состояния не является распределённой блокировкой; одновременные запросы к одной session из разных клиентов не поддерживаются.

При timeout локальное ожидание прекращается, удалённый turn автоматически не отменяется. Не повторяйте сообщение автоматически: повтор может отправить его ещё раз; без `sessionId` он создаст новую session. В случае ошибки первого запроса созданный ID может не попасть к клиенту.

Общий лимит ожидания — 180 секунд, connect timeout — 10 секунд, период опроса — 500 мс. Оставшееся время ограничивает каждый последующий HTTP-вызов. Поиск товаров, Camunda и прочие tools пока не реализованы; встроенные `web_search` и `generate_fence_image` обрабатываются текущим flow.

## Ошибки

Формат: `{"code":"...","error":"..."}`. Ключ, тела ошибок OpenAI и stack trace не возвращаются и не логируются.

| Ситуация | HTTP |
| --- | --- |
| Пустое/отсутствующее message, некорректный JSON | 400 |
| Пустой sessionId или session другого агента | 400 |
| Переданная session не найдена при проверке OpenAI | 404 |
| Session уже занята | 409 |
| Нет OPENAI_API_KEY или OPENAI_AGENT_ID | 503 |
| OpenAI 4xx (кроме 429) | 502 |
| OpenAI 429 / 5xx, ошибка соединения | 503 |
| Timeout | 504 |
| Неуспешный turn, требуется tool, неверный/пустой ответ | 502 |
| Неожиданная внутренняя ошибка | 500 |

В `application.yml` используются `${OPENAI_API_KEY:}` и `${OPENAI_AGENT_ID:}` с пустыми default: это позволяет приложению стартовать без секретов, сохранить health доступным и вернуть понятный 503 на chat. Секретов и резервного ключа в конфигурации нет.

## Диагностика polling и встроенного web_search

## `generate_fence_image`

При `required_action` с `name=generate_fence_image` сервис выполняет последовательность:

`Managed Agent → required_action → POST /v1/images/generations (gpt-image-2.5-flare) → проверка data[0].b64_json → PNG decode → ./generated-images/<uuid>.png → tool_result → продолжение текущего turn → AgentChatResponse.images`.

Image API для GPT Image возвращает `data[]` с `b64_json`; `response_format` для GPT Image не используется. Успешный tool result отправляется только после проверки base64, PNG signature/decoder, `Files.exists(path)` и `Files.size(path) > 0`. В `output` tool result передаётся только JSON с `success`, `imageId` и `imageUrl`, без base64. При ошибке сервис отправляет `success=false` с кратким кодом ошибки; картинка в `images[]` не добавляется.

`arguments` function call принимаются в двух формах, которые встречаются в API-интеграциях: JSON-объект и JSON-строка. Сервис логирует имя функции, fenceType/color (ограниченно), HTTP status Image API, наличие и длину base64, ID, рабочую директорию, абсолютный путь и размер файла. Base64, prompt, API key и Authorization не логируются.

Изображение доступно через `GET /api/v1/images/{uuid}` с `Content-Type: image/png`. ID строго проверяется как UUID, а чтение не следует символическим ссылкам.

## `start_camunda_approval`

После явного подтверждения пользователя Managed Agent создаёт `required_action` с `name=start_camunda_approval`. Сервис вызывает document-service:

`Managed Agent → required_action → POST ${DOCUMENT_SERVICE_BASE_URL}/api/v1/processes/document-approval → tool_result → продолжение того же turn`.

По умолчанию используется `DOCUMENT_SERVICE_BASE_URL=http://localhost:8081`. В document-service отправляются только фиксированные demo-поля: `APP-FENCE-001`, `Николай Николаевич`, `FENCE_APPROVAL`; бюджет, материалы, цвет и изображение в Camunda не передаются. Успешный response document-service имеет `processInstanceKey`, который возвращается агенту в `tool_result.output`.

Общий timeout **180 секунд**, connect timeout **10 секунд** и polling interval **500 мс** сохранены. Каждый polling iteration пишет INFO `OpenAI poll`:

- `iteration`, `sessionId`, `sessionStatus`, `rootTurnId`, `turnStatus`;
- `sessionErrorPresent`, `turnErrorPresent`, безопасный `turnErrorCode`;
- `requiredActionsCount`, `requiredActionTypes`;
- `itemsCount`, `itemsFetched`, `lastItemType`, `lastItemStatus`;
- `webSearchPresent`, `webSearchStatuses` и технический `reason`.

Items читаются постранично в каждой итерации; на успешном завершении эти же items используются для ответа. `itemsCount` — число полученных items session во всех прочитанных страницах, последний item определяется по `order=asc`. `webSearchStatuses` включает только вызовы поиска текущего root turn, чтобы поиск из предыдущего сообщения не смешивался с текущим.

Если ошибка session/turn уже известна, дополнительные запросы items не выполняются: диагностический HTTP timeout не должен скрывать исходную ошибку. В такой записи `itemsFetched=false`, `itemsCount=0` (ничего не получено), `webSearchPresent=unknown`, а неизвестные поля — `not_observed`. При сбое чтения страниц `itemsCount` может отражать частично полученные данные. Это не означает отсутствия items в OpenAI. INFO `OpenAI polling stopped` связывает ошибку чтения следующей session/timeout с её ID и последним известным root turn.

В логах нет prompt, ответа модели, текста ошибок OpenAI, аргументов tools, поисковых запросов, URL результатов, API key и Authorization. Типы/статусы и коды ошибок выводятся только из разрешённого набора протокольных значений; неизвестные значения — `unrecognized`. ID очищаются от небезопасного формата.

Проверка актуального контракта:

| Объект | Состояния и значение |
| --- | --- |
| Turn | `queued`, `in_progress` — работа ещё не завершена; `waiting` — ожидает внешний ввод; terminal: `completed`, `failed`, `cancelled` |
| Session | `idle`, `in_progress`, `requires_action`, `failed`; `failed` — ошибка, `requires_action` — блокировка; `idle` само по себе не подтверждает успешное завершение turn |
| `web_search_call` item | Поля `id`, `type: web_search_call`, `turn_id`, `status`, опциональный `action`; статусы `in_progress`, `completed`, `incomplete` |

Источники: [turn API](https://developers.openai.com/api/reference/ruby/resources/beta/subresources/agents/subresources/sessions/subresources/turns/methods/list), [session API](https://developers.openai.com/api/reference/ruby/resources/beta/subresources/agents/subresources/sessions/methods/retrieve), [схемы AgentWebSearchCallItem / AgentOutputItemStatus](https://developers.openai.com/api/reference/python/resources/beta/subresources/agents), [встроенный web search](https://developers.openai.com/api/docs/guides/agents-api/tools/web-search), [functions](https://developers.openai.com/api/docs/guides/agents-api/tools/functions).

Встроенный `web_search` выполняет OpenAI, в том числе с `environment.type=none`. Документированный flow не требует возвращать внешний tool result для этого поиска. `required_actions` описывают `function_call` или `environment_connection`; если они возникли, это отдельное ожидание внешнего действия, которое сервис не выполняет. `web_search_call.completed` не означает `turn.completed`: агент ещё может формировать итоговый ответ. Аналогично `web_search_call.incomplete` не заменяет исход всего turn — агент может завершиться с текстовым объяснением ошибки поиска.

Ошибки polling возвращаются как HTTP 502 с конкретным `code`:

- `AGENT_SESSION_ERROR`, `AGENT_TURN_ERROR` — непустой `error` даже при внешне нетерминальном статусе;
- `AGENT_SESSION_FAILED`, `AGENT_TURN_FAILED` — ошибка session либо failed/cancelled turn;
- `AGENT_REQUIRES_ACTION`, `AGENT_TURN_WAITING_EXTERNAL_INPUT` — требуется внешний ввод;
- `AGENT_IDLE_WITHOUT_COMPLETED_TURN` — защитная ошибка для невозможного состояния активного существующего turn; после continuation event `idle` без нового turn считается временным состоянием и polling продолжается до появления нового turn или общего timeout;
- `AGENT_TURN_INCOMPLETE`, `AGENT_SESSION_INCOMPLETE`, `AGENT_SESSION_CANCELLED` — защитная обработка этих значений, если они пришли. Они не входят в документированный enum соответствующих объектов (у turn документирован `cancelled`).

`OPENAI_TIMEOUT` сохраняется для реально истёкшего ожидания/HTTP-вызова, пока terminal или блокирующее состояние ещё не получено. Уже известная ошибка не заменяется timeout. Для определения причины конкретного зависания нужны записи `OpenAI poll`/`OpenAI polling stopped` реального запроса: наличие подключённого web_search само по себе причину не устанавливает.

## Проверки

```sh
mvn clean test
mvn clean package
java -jar target/ai-agent-service-0.0.1-SNAPSHOT.jar
```

Тесты controller, service, validation и HTTP-клиента работают без реальных ключей. HTTP-тесты используют локальный mock server и проверяют первый запрос, продолжение той же session без повторного создания, новый turn вместо исторического, заголовки, pagination, invalid sessionId, ошибки 4xx/5xx и timeout. Для проверки реального ответа нужны действующие env и доступ к сохранённому агенту.

## Nearby Places Agent

`POST /api/agents/nearby-places` запускает отдельного логического агента в этом же приложении:
`NearbyPlacesController → NearbyPlacesAgentService → OpenAiAgentClient.runAgent(...)`.
HTTP transport, deadline, обработка ошибок и пагинация переиспользуются из существующего клиента.
Текущий chat flow с turns и tools сохранён. Платформа проекта остаётся Java 21 / Spring Boot 4.1.1;
переход на Boot 3 в рамках добавления endpoint не выполнялся.

Настройки в `application.yml` / окружении:

| Property | Env | По умолчанию |
| --- | --- | --- |
| `openai.api-key` | `OPENAI_API_KEY` | пусто; без ключа endpoint возвращает 503 |
| `openai.agents.nearby-places.id` | `OPENAI_NEARBY_PLACES_AGENT_ID` | `agent_2fc10e837c4b4ef3bab6cb6b0659b6d2f8a8a83acb5f4cdcb6` |
| `openai.polling.interval-ms` | `OPENAI_POLLING_INTERVAL_MS` | `750` |
| `openai.polling.timeout-ms` | `OPENAI_POLLING_TIMEOUT_MS` | `60000` |

Последние две настройки относятся к структурированному `runAgent`: общий deadline включает создание
session, polling и чтение всех страниц items. Для существующего чата сохранены 180 секунд / 500 мс.
Интервалы должны быть положительными; для рабочего окружения рекомендуется polling 500–1000 мс.
`OPENAI_AGENT_ID` остаётся настройкой прежнего чата и не нужен для Nearby Places.

Запрос сериализуется внедрённым Spring ObjectMapper в JSON-строку поля `input`.
Новая session создаётся с `environment.type=none`, `stream=false`; при `in_progress` выполняется polling,
при `idle` читаются items с `order=asc&limit=100`, включая последующие страницы.
Из последнего `message / assistant / final_answer / completed` извлекается `content[0].text`.
Строка десериализуется в `NearbyPlacesResponse`; backend возвращает только `places`.
`{"places":[]}` — корректный ответ 200. Отсутствующий/null `places` превращается в пустой список; null-элементы удаляются при post-processing.

```sh
curl --fail-with-body -X POST http://localhost:8082/api/agents/nearby-places \
  -H 'Content-Type: application/json' \
  -d '{
    "city": "Москва",
    "date": "2026-09-29",
    "timeFrom": "18:00",
    "timeTo": "23:00",
    "budget": 20000,
    "company": "couple",
    "preferences": ["cafe", "restaurant"],
    "event": {
      "id": 226684,
      "title": "мюзикл «Плакса»",
      "address": "Пушкинская пл., д. 2",
      "lat": 55.7658,
      "lon": 37.6050,
      "price": 1000,
      "source": "KudaGo"
    }
  }'
```

Ошибки возвращаются существующим `GlobalExceptionHandler` в формате `{"code":"...","error":"..."}`:

- 400 `INVALID_REQUEST` — невалидный JSON, обязательные поля, дата, время HH:mm, бюджет или координаты.
- 502 `AGENT_SESSION_FAILED`, `AGENT_SESSION_CANCELLED`, `AGENT_SESSION_ERROR` — ошибка session.
- 502 `MISSING_FINAL_ANSWER` — нет завершённого итогового сообщения.
- 502 `INVALID_AGENT_JSON` — невалидный JSON/структура бизнес-ответа.
- 502 `INVALID_OPENAI_RESPONSE` / `OPENAI_REQUEST_REJECTED` — нарушение протокола или отказ OpenAI.
- 503 `OPENAI_NOT_CONFIGURED`, `OPENAI_RATE_LIMIT`, `OPENAI_UNAVAILABLE`, `OPENAI_CONNECTION_ERROR` — конфигурация или недоступность upstream.
- 504 `OPENAI_TIMEOUT` — истёк общий deadline либо HTTP timeout.

Логируются безопасный sessionId, статус и код ошибки; ключ, Authorization, входной JSON,
ответ агента и тело ошибки OpenAI в логи не попадают.
Тесты `NearbyPlacesAgentClientTest` и `NearbyPlacesControllerTest` работают без реального OpenAI;
полная сборка с регрессионными тестами: `./mvnw clean package`.


### Нормализация Nearby Places

После десериализации `final_answer` метод `NearbyPlacesAgentService.normalize(...)` выполняет
post-processing через перегрузку `runAgent(..., postProcessor)`. Клиент передаёт безопасный для логов
sessionId и DTO, сохраняя прежний вариант `runAgent` и HTTP/polling без изменений.

- Удаляет null-элементы и места с `openAtRequestedTime=false`; `null` означает неизвестные часы работы и сохраняется.
- Применяет `trim()` к title/type/address/reason/source/sourceUrl. Пустые address/source/sourceUrl становятся null;
  title не преобразуется из пустой строки в null.
- Из обычной Markdown-ссылки `[текст](https://example.com)` извлекает URL. Обычные HTTP(S) URL сохраняет;
  некорректные/неподтверждённые ссылки заменяет null. Сложный Markdown не разбирается.
- Пересчитывает distanceMeters по Haversine в `GeoDistance`, если обе пары координат присутствуют,
  конечны и находятся в допустимых диапазонах. Расстояние по прямой округляется до метра и возвращается как Double;
  это не длина пешеходного маршрута. Без координат сохраняется значение агента, включая null или 0.
- Не изменяет estimatedPrice и lat/lon, не выполняет geocoding.
- Оставляет пустой итоговый список валидным результатом. Null вместо всего ответа и malformed JSON остаются ошибкой 502.

INFO `Nearby places normalized` содержит sessionId, placesBefore, placesAfter,
sourceUrlsNormalized и distancesRecalculated. placesBefore включает null-элементы исходного списка;
счётчики URL и расстояний относятся к оставшимся местам. Изменение URL включает trim, извлечение Markdown URL
и замену некорректной ссылки на null. Содержимое ответа и секреты не логируются.

Инструкции сохранённого агента в репозитории не обнаружены. В OpenAI UI добавьте вручную:

> Поле sourceUrl всегда возвращай как обычную абсолютную URL-строку. Не используй Markdown-ссылки, квадратные или круглые скобки. Если URL не удалось подтвердить, верни null.

> Не возвращай места, если точно известно, что openAtRequestedTime = false.

Backend не обновляет определение агента автоматически. agent_id сохранён.
