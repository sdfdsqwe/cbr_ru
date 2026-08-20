
## Требования

- JDK 21+
- Maven 3.6+
- Запущенный PostgreSQL

## Конфигурация
Пример файла конфигурации - config.properties.example.
Параметры передаются через системное свойство config.file.

## Сборка

```bash
mvn clean package
```

## Запуск

```bash
java --% -Djava.util.logging.config.file=logging.properties \
     -Dconfig.file=config.properties \
     -jar target/cbr_ru-1.0.0.jar
```

Логи пишутся в консоль и в папку `logs/` (папка в Git не хранится).

## Эндпоинты

### GET /api/v1/rates - получить курс валюты

Запрос:

```bash
curl "http://localhost:8080/api/v1/rates?targetCurrency=USD&date=2026-08-20"
```

Ответ:

```json
{
  "target_currency": "USD",
  "date": "2026-08-06",
  "rate": "83,1259",
  "cached_at": "2026-08-20T10:20:30Z"
}
```

Возможные ошибки: 
- 400 — неверные параметры 
- 502 — ЦБ РФ недоступен
- 503 — БД недоступна (курс всё равно отдаётся из ЦБ, кэш пропускается).

### GET /api/v1/cache/rates - статистика кэша

```bash
curl "http://localhost:8080/api/v1/cache/rates"
```

```json
{
  "cache_kind": "rates",
  "cache_ttl": "3600",
  "cache_size": 2,
  "cache_size_max": 100,
  "rates": [
    {"target_currency": "USD", "date": "2026-08-06", "rate": "83,1259", "cached_at": "2026-08-06T10:20:30Z"}
  ]
}
```

### DELETE /api/v1/cache/rates?date=2026-08-06 - очистить кэш за дату

```bash
curl -X DELETE "http://localhost:8080/api/v1/cache/rates?date=2026-08-20"
```

### DELETE /api/v1/cache/rates - очистить весь кэш

```bash
curl -X DELETE "http://localhost:8080/api/v1/cache/rates"
```

Ответ операций очистки:

```json
{"status": "ok", "deleted": 1}
```

## Тестирование

Коллекция Postman с автотестами лежит в папке postman/
(файл `CBR_Rates_API.postman_collection.json`).

## Структура проекта

```text
src/main/java/com/example/
 Main.java         - точка входа, запуск сервера
 AppConfig.java    - загрузка конфигурации с дефолтами
 RateHandler.java  - обработчик GET /api/v1/rates (кэш + ЦБ)
 CacheHandler.java - обработчик GET/DELETE /api/v1/cache/rates
 RatesDao.java     - вся работа с PostgreSQL
 CbrClient.java    - клиент API ЦБ РФ (AutoCloseable)
 HttpUtils.java    - отправка JSON, разбор query-параметров
```