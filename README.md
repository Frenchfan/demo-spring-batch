# Spring Batch: демонстрационные данные

Java 25, Spring Boot 4.1.1, H2 в памяти и embedded MongoDB 7.0.28.
PostgreSQL и Docker не нужны. Реализованы общий Tasklet исправления и восемь
chunk Job, повторные запуски и restart с checkpoint после сбоя.
Все названия компаний, ФИО и адреса синтетические, совпадения случайны.

## Запуск

Из корня проекта, с JDK 25 в JAVA_HOME и установленным Maven:

```powershell
mvn clean spring-boot:run
```

При первом запуске Flapdoodle может скачать MongoDB (нужен доступ к сети).
Приложение слушает порт 8081, embedded MongoDB — 27027.
Flyway создаёт и наполняет H2 до проверки схемы Hibernate; Mongock выполняется
в ApplicationRunner после инициализации контекста и читает эталон из H2.
Успешным завершением наполнения считается сообщение Mongock об окончании миграции.

Каждый запуск создаёт чистые базы и заново выполняет Flyway и Mongock:

- H2: `jdbc:h2:mem:batch-demo-v1`, схема `TEST`. После закрытия приложения
  исчезают все таблицы, включая историю Flyway и служебные таблицы Batch.
- MongoDB: база `batch_demo_v1` во временном каталоге Flapdoodle.
  При следующем запуске создаётся новый каталог, данные и история Mongock не сохраняются.

Исправления Batch действуют до остановки приложения. После перезапуска снова
доступны исходные 1500 заявок и 120 документов с ошибками.
Старые файловые базы в `data/` больше не используются и автоматически не удаляются.
H2 Console внутри приложения подключается к той же базе в памяти; подключение
из отдельного процесса к этому JDBC URL создаст другую базу.

## Состав H2

| Таблица | Строк | Содержимое |
| --- | ---: | --- |
| INDUSTRY | 12 | Отрасли производства и ИТ с кодами ОКВЭД |
| COMPANY | 300 | Компании из 15 городов, юридические и фактические адреса |
| EMPLOYEE | 900 | По три сотрудника компании: экспорт, финансы, субсидии |
| SUBSIDY | 1500 | По пять заявок компании, суммы и ответственный сотрудник |

Итого 2712 строк предметной области; 1500 — число заявок, а не сумма всех таблиц.
Запрошенные суммы примерно 250 тыс.–5 млн руб.; одобрение обычно 50–90%,
каждая 13-я заявка имеет нулевое одобрение. Суммы хранятся как NUMERIC(19,2).
Ответственный всегда работает в компании заявителя. Компания имеет постоянную
отрасль во всех пяти заявках.

`V1__create_domain_tables.sql` создаёт таблицы, ограничения и индексы,
`V2__seed_demo_data.sql` вставляет фиксированный набор данных. Hibernate только
проверяет схему (`ddl-auto: validate`). Связи Subsidy → Company и Industry имеют
тип ManyToOne: одна компания и отрасль могут относиться к нескольким заявкам.
Начало автоматических ID — 10001, чтобы новые записи не пересекались с seed-ID.
Применённые Flyway-миграции не редактируют: последующие изменения добавляются в V3 и далее.

H2 Console: http://localhost:8081/h2-console

- JDBC URL: `jdbc:h2:mem:batch-demo-v1`
- User: `sa`, password: `password`.

```sql
SELECT COUNT(*) FROM TEST.SUBSIDY; -- 1500
SELECT s.id, c.title, i.name AS industry, e.name AS responsible,
       s.requested_subsidy, s.approved_subsidy
FROM TEST.SUBSIDY s
JOIN TEST.COMPANY c ON c.id = s.company_id
JOIN TEST.INDUSTRY i ON i.id = s.industry_id
JOIN TEST.EMPLOYEE e ON e.id = s.person_in_charge_id
ORDER BY s.id;
```

## Коллекция MongoDB

Подключение Compass/mongosh: `mongodb://localhost:27027/batch_demo_v1`
(пока приложение работает).

`subsidy_applications`: 1500 документов, один документ на заявку. Объединяет все
поля Subsidy, Company, Industry и ответственного Employee; остальных сотрудников
компании в документе нет. `subsidyId` — стабильный ключ сопоставления с H2,
на него создан уникальный индекс. `_id` имеет вид `subsidy-v1-1`.

```javascript
{
  _id: "subsidy-v1-1",
  dataset: "subsidies-v1",
  schemaVersion: 1,
  subsidyId: NumberLong(1),
  requestedSubsidy: NumberDecimal("257919.37"),
  approvedSubsidy: NumberDecimal("147014.04"),
  company: {
    id: NumberLong(1),
    title: "  ООО «Вектор АгроПродукт»  ", // намеренные пробелы
    legalAddress: "г. Пермь, ул. Центральная, д. 14, офис 2",
    factAddress: "г. Пермь, ул. Промышленная, д. 2, корпус 2"
  },
  industry: { id: NumberLong(1), name: "Производство пищевых продуктов", code: "10.89" },
  personInCharge: {
    id: NumberLong(1), name: "Алексеев Михаил Александрович",
    position: "Руководитель экспортного отдела", companyId: NumberLong(1)
  }
}
```

Числовые ID — BSON int64, денежные значения — BSON Decimal128 (кроме сценария 8).
Batch Reader использует модель `SubsidyApplication` с `@Document`. Поле
`requestedSubsidy` имеет тип `Object`, поэтому ошибочный String не превращается
незаметно в число при чтении. Остальная структура описана типизированными полями.

## Намеренные ошибки

1380 документов полностью соответствуют H2. В 120 документах ровно по одной
ошибке. Для каждого сценария — 15 документов; сценарии не пересекаются.
Номер сценария определяется `subsidyId % 100`; остальных значений ошибки не касаются.
Метки ошибок в самих документах нет. Processor проверяет значения и типы полей;
остаток от деления subsidyId используется в Reader для выбора учебного сценария.

| Остаток | Пример ID | Поле | Ошибка | Способ исправления |
| --- | --- | --- | --- | --- |
| 1 | 1, 101, …, 1401 | company.title | Лишние пробелы по краям | trim / эталон H2 |
| 2 | 2, 102, …, 1402 | company.legalAddress | Поле отсутствует | Восстановить из H2 |
| 3 | 3, 103, …, 1403 | company.factAddress | Устаревший московский адрес | Сверить с H2 |
| 4 | 4, 104, …, 1404 | approvedSubsidy | Выше запрошенной на 10000 | Восстановить одобренную сумму из H2 |
| 5 | 5, 105, …, 1405 | requestedSubsidy | Отрицательная сумма | Сверить с H2 |
| 6 | 6, 106, …, 1406 | industry.code | Код 99.99 вне справочника | Восстановить по industry.id |
| 7 | 7, 107, …, 1407 | personInCharge.id | Несуществующий ID 999999 | Восстановить по subsidyId из H2 |
| 8 | 8, 108, …, 1408 | requestedSubsidy | Строка вместо Decimal128 | Преобразовать и сверить с H2 |

Проверки в mongosh после подключения к нужной базе:

```javascript
db.subsidy_applications.countDocuments({}) // 1500
db.subsidy_applications.findOne({ subsidyId: NumberLong(2) })
db.subsidy_applications.countDocuments({ "company.legalAddress": { $exists: false } }) // 15
db.subsidy_applications.countDocuments({ requestedSubsidy: { $type: "string" } }) // 15
db.subsidy_applications.countDocuments({ requestedSubsidy: { $lt: NumberDecimal("0") } }) // 15
```

## Повторный запуск и демонстрация исправлений

Для сброса демонстрации достаточно остановить и снова запустить приложение.
Данные обеих баз, история Flyway/Mongock и метаданные Batch создаются заново.
Никакие файлы вручную переименовывать или удалять не нужно.

В пределах одного запуска повторный вызов Mongock не портит уже исправленные
документы. При частичном сбое фиксированные `_id` и `$setOnInsert` позволяют
добавить недостающие документы без замены существующих. Rollback намеренно
не удаляет частичные вставки. Транзакции Mongock выключены, поскольку embedded
MongoDB работает без replica set.

H2 — эталон. После первоначального наполнения изменения H2 автоматически
в MongoDB не переносятся — это работа будущего Batch job.

## Служебная схема Spring Batch

Проект использует Spring Batch **6.0.5**, версию выбирает Spring Boot 4.1.1.
Подключён `spring-boot-starter-batch-jdbc`, чтобы JobRepository сохранял метаданные
в H2. Одного общего `spring-boot-starter-batch` для JDBC-инфраструктуры недостаточно.

`V3__create_spring_batch_metadata.sql` содержит исходный скрипт
`org/springframework/batch/core/schema-h2.sql`, извлечённый из подключённого
`org.springframework.batch:spring-batch-core:6.0.5`. SQL скопирован без изменений,
в заголовке записано его происхождение. Flyway применяет его в схеме `TEST`.

| Объект | Назначение |
| --- | --- |
| BATCH_JOB_INSTANCE | Идентичность задания: имя и ключ идентифицирующих параметров |
| BATCH_JOB_EXECUTION | Попытки запуска, статусы, время и результат |
| BATCH_JOB_EXECUTION_PARAMS | Параметры конкретной попытки запуска |
| BATCH_STEP_EXECUTION | Выполнение шагов и счётчики обработки |
| BATCH_JOB_EXECUTION_CONTEXT | Контекст выполнения задания |
| BATCH_STEP_EXECUTION_CONTEXT | Контекст шага, включая состояние для возобновления |

Также исходный скрипт создаёт три sequence. Все объекты находятся в `TEST`,
а `spring.batch.jdbc.table-prefix: TEST.BATCH_` направляет запросы репозитория туда.
`spring.batch.jdbc.initialize-schema: never` отключает второй механизм создания
таблиц. Единственный владелец DDL — Flyway. `@EnableBatchProcessing` вручную
не добавляем: инфраструктуру настраивает JDBC-autoconfiguration Spring Boot.

**Один раз на базу:** повторный вызов Flyway для уже созданной базы не выполняет
V3 и не удаляет историю заданий. При перезапуске приложения прежний режим сброса
остаётся в силе: H2 в памяти исчезает, новый запуск применяет V1–V3 заново.
Для демонстрации восстановления после завершения JVM понадобится отдельный
режим с постоянным JobRepository и сохранёнными исходными/целевыми данными.

Рабочий job исправления MongoDB ещё не реализован. `spring.batch.job.enabled: false`
оставляет автоматический запуск заданий выключенным. В тесте небольшой job
проверяет JDBC-инфраструктуру, параметры и сохранение ExecutionContext.

Запросы для H2 Console:

```sql
SELECT * FROM TEST."flyway_schema_history" ORDER BY "installed_rank";
SELECT * FROM TEST.BATCH_JOB_INSTANCE;
SELECT JOB_EXECUTION_ID, STATUS, START_TIME, END_TIME, EXIT_CODE
FROM TEST.BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID;
SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT, COMMIT_COUNT, ROLLBACK_COUNT
FROM TEST.BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID;
```

До первого запуска задания таблицы метаданных пустые. Тестовые запуски используют
отдельную H2 и не оставляют записи в базе запущенной демонстрации.
При обновлении Spring Batch сравните встроенный DDL с V3 и добавьте новую
Flyway-миграцию при необходимости. Уже применённую V3 не перезаписывайте.
Тест сравнения SQL с ресурсом зависимости обнаружит изменение встроенной схемы.

Источники: [схема метаданных Spring Batch](https://docs.spring.io/spring-batch/reference/schema-appendix.html),
[настройка JobRepository](https://docs.spring.io/spring-batch/reference/job/configuring-repository.html).

## Проверка

## Запуск Batch-задач во время работы приложения

Автоматический запуск заданий выключен свойством `spring.batch.job.enabled: false`.
Приложение сначала создаёт H2, таблицы Spring Batch и MongoDB, после чего ожидает
явного запуска выбранного Job.

Первый реализованный сценарий исправляет MongoDB по эталону H2:

```http
POST http://localhost:8081/api/batch/jobs/repair-subsidies
```

Для PowerShell:

```powershell
mvn spring-boot:run
curl.exe -X POST http://localhost:8081/api/batch/jobs/repair-subsidies
```

Ответ содержит `jobName`, `executionId`, `status` и `exitCode`. Вызов создаёт новую
`JobExecution` с параметрами `dataset=subsidies-v1` и уникальным `runId`. Job читает
эталонные 1500 заявок из H2, сравнивает их с `subsidy_applications` и заменяет
отличающиеся документы. В текущем наборе исправляются 120 документов.

Дополнительные сценарии запускаются тем же HTTP-механизмом:

```powershell
curl.exe -X POST http://localhost:8081/api/batch/jobs/audit-subsidies
curl.exe -X POST http://localhost:8081/api/batch/jobs/quarantine-subsidies
```

Для восьми отдельных дефектов доступны восемь Job:

```powershell
curl.exe -X POST http://localhost:8081/api/batch/jobs/repairDefect01Job
curl.exe -X POST http://localhost:8081/api/batch/jobs/repairDefect02Job
curl.exe -X POST http://localhost:8081/api/batch/jobs/repairDefect03Job
curl.exe -X POST http://localhost:8081/api/batch/jobs/repairDefect04Job
curl.exe -X POST http://localhost:8081/api/batch/jobs/repairDefect05Job
curl.exe -X POST http://localhost:8081/api/batch/jobs/repairDefect06Job
curl.exe -X POST http://localhost:8081/api/batch/jobs/repairDefect07Job
curl.exe -X POST http://localhost:8081/api/batch/jobs/repairDefect08Job
```

Они соответствуют восьми сценариям из раздела «Намеренные ошибки»: название,
отсутствующий адрес, устаревший адрес, неверная одобренная сумма, отрицательная
запрошенная сумма, неверный код отрасли, несуществующий сотрудник и строковый тип
суммы. Каждый Job использует `subsidyId % 100` как селектор учебного сценария.
Счётчики chunk-обработки доступны в `StepExecution`: `readCount`, `writeCount`,
`filterCount`, `skipCount`. Пользовательский `repairedCount` записывает только
общий Tasklet `repairSubsidiesJob`.

`auditSubsidiesJob` записывает в `ExecutionContext` ожидаемое и фактическое количество
документов, а `quarantineSubsidiesJob` пока только считает документы с `demoError=true`.
Seed не добавляет этот признак, поэтому на исходном наборе результат равен нулю.
Реальная запись пропущенного элемента в `subsidy_quarantine` реализована в
`SkipListener` восьми chunk Job. Эти два механизма пока не связаны.
Контроллер использует `JobOperator`: прямой `JobLauncher` не внедряется, поскольку в
Spring Batch 6 интерфейс `JobLauncher` deprecated и помечен к удалению. `JobOperator`
также предоставляет операции restart/stop/recover.

Если нужен отдельный запрос состояния:

```powershell
curl.exe http://localhost:8081/api/batch/executions/1
```

Вместо `1` подставьте `executionId` из ответа запуска.

Метаданные запуска доступны в H2 Console:

```sql
SELECT * FROM TEST.BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID DESC;
SELECT * FROM TEST.BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC;
```

Общий `repairSubsidiesJob` реализован как Tasklet. Восемь `repairDefectXXJob`
уже используют chunk, `ItemReader` / `ItemProcessor` / `ItemWriter`, retry/skip
и `SkipListener` для записи в quarantine.

Tasklet выполняет одну целостную операцию через `execute` и возвращает
`RepeatStatus.FINISHED`. Это удобно для короткой процедуры, очистки или подготовки.
Chunk предназначен для потока элементов: `ItemReader` читает по одному,
`ItemProcessor` преобразует, `ItemWriter` получает результаты порции. После
фильтрации их может быть меньше размера chunk. В кейсе 08 размер порции — 10,
в остальных — 25. JDBC transaction manager не откатывает записи standalone MongoDB.

Reader `SubsidyApplicationReader` и Processor имеют `@StepScope`: новый экземпляр
создаётся для каждого StepExecution. Чтение Mongo происходит в `open()` после seed,
эталоны H2 загружаются при первом обращении к Processor в выполняющемся Step.
Новый JobInstance читает актуальные документы с начала; исправные элементы
Processor фильтрует без повторной записи.

### Новый запуск и продолжение после сбоя

`SubsidyApplicationReader` реализует `ItemStreamReader<SubsidyApplication>`:

- `open(context)` восстанавливает `subsidyReader.lastId` (по умолчанию 0).
- Запрос выбирает `subsidyId > lastId` и `subsidyId % 100 == scenario`, сортирует по ID.
- `read()` выдаёт очередной документ и запоминает его ID.
- `update(context)` сохраняет ключ для checkpoint успешной порции.
- `close()` освобождает список. В этом небольшом демо выбранные документы хранятся в памяти.

Обычный POST `/api/batch/jobs/repairDefect08Job` создаёт новый JobInstance с новым
runId. Для restart нужно сохранить параметры прежнего JobInstance и передать
ID неудачного выполнения:

```powershell
curl.exe -X POST http://localhost:8081/api/batch/executions/1/restart
```

Вместо 1 укажите executionId со статусом FAILED или STOPPED. Контроллер вызывает
`jobOperator.restart(previousExecution)` и возвращает новый executionId того же
JobInstance. Неизвестный ID даёт 404, неподходящий статус — 409.

MongoDB и JDBC не образуют общую транзакцию. Если Mongo-запись прошла до сбоя,
restart повторно прочитает незавершённую порцию и отфильтрует уже исправленные
документы. Ключи subsidyId и исходный набор должны оставаться стабильными между
попытками; новые документы с ID ниже checkpoint требуют нового запуска с начала.
После перезапуска приложения embedded-базы сбрасываются, поэтому restart старой
истории возможен только пока приложение продолжает работать.

```powershell
mvn test
```

Интеграционный тест использует отдельную H2 в памяти, временный каталог MongoDB
и свободный порт. Он проверяет количества и связи, 1380 точных совпадений,
все 8 типов дефектов, повторное выполнение Flyway/Mongock и возобновление
частичной загрузки без потери уже исправленного документа. Рабочие базы не меняются.

Mongock подключён через [standalone runner](https://docs.mongock.io/v5/runner/standalone/index.html),
который явно запускается из Spring; отдельная автоконфигурация для старых версий Boot не нужна.

Отдельный тест перезапуска закрывает и повторно открывает приложение, проверяя,
что произвольные таблицы/коллекции исчезают, а исходные данные восстанавливаются.

### API Spring Batch 6

Chunk Step использует актуальный builder:

```java
new StepBuilder("repairStep", jobRepository)
    .<SubsidyApplication, Document>chunk(25)
    .transactionManager(transactionManager)
    .reader(reader).processor(processor).writer(writer)
    .faultTolerant()
    .skipLimit(5).skip(IllegalArgumentException.class)
    .skipListener(skipListener)
    .retryLimit(3).retry(RuntimeException.class)
    .build();
```

`chunk(25)` задаёт размер порции. Менеджер транзакций передаётся отдельным методом.
`skipListener(...)` регистрирует обработчик пропущенных элементов.
Запуск: `JobOperator.start(Job, JobParameters)`; вариант с именем и Properties устарел.
В pom включён showDeprecation для main и test; проверка — `mvn clean test`.

### Модель MongoDB

Коллекция описана в `mongo/SubsidyApplication.java` аннотацией
`@Document(collection = "subsidy_applications")`. Это Java record с вложенными
`Company`, `Industry` и `PersonInCharge`: они хранятся внутри того же документа,
а не в отдельных коллекциях. `@MongoId String id` соответствует BSON `_id`.

Поля верхнего уровня: `id`, `dataset`, `schemaVersion`, `subsidyId`,
`requestedSubsidy`, `approvedSubsidy`, `company`, `industry`, `personInCharge`.
Компания содержит `id`, `title`, `legalAddress`, `factAddress`; отрасль — `id`,
`name`, `code`; ответственный — `id`, `name`, `position`, `companyId`.

Восемь chunk Job читают модель через `mongo.find(query, SubsidyApplication.class)`
в `SubsidyApplicationReader.open()`. Query содержит checkpoint и порядок чтения.
Processor теперь имеет тип `ItemProcessor<SubsidyApplication, org.bson.Document>`:
проверяет типизированный вход, а результат из H2 передаёт существующему Writer.
`requestedSubsidy` намеренно объявлен как `Object`: исходный BSON Decimal128
и ошибочный String должны оставаться различимыми. Для `approvedSubsidy`
используется `Decimal128`. Отсутствующий legalAddress читается как null.
Mongock по-прежнему формирует сырой BSON для воспроизведения восьми дефектов.

```java
@Document(collection = SubsidyApplication.COLLECTION)
public record SubsidyApplication(
        @MongoId String id,
        String dataset, Integer schemaVersion, Long subsidyId,
        Object requestedSubsidy, Decimal128 approvedSubsidy,
        Company company, Industry industry, PersonInCharge personInCharge) {
    public static final String COLLECTION = "subsidy_applications";
    // Вложенные records определены в файле модели.
}
```

`@Document` — аннотация Spring Data MongoDB. `org.bson.Document` — отдельный
класс контейнера BSON; он остаётся выходным типом Processor и входом Writer.

```java
ItemProcessor<SubsidyApplication, Document> processor = item -> {
    Document source = expected.get(item.subsidyId());
    if (source == null) {
        throw new IllegalArgumentException("Unknown subsidyId " + item.subsidyId());
    }
    if (!hasDefect(item, source, Math.toIntExact(item.subsidyId() % 100))) return null;
    return source;
};
```

Эталон `expected` строится запросом H2: subsidy JOIN company/industry/employee.
Он индексируется по `subsidyId`. Например, для устаревшего фактического адреса
Processor проверяет `company.factAddress()`, а Writer заменяет весь документ
эталоном заявки. Отдельного запроса по `company.id` в Processor сейчас нет.
Writer использует `replaceOne` без upsert и не создаёт отсутствующие документы.

Вложенные BSON-поля `company.id`, `industry.id`, `personInCharge.id` сопоставлены
с Java `@Field("id") Long sourceId`. Это исключает автоматическое отображение
Java-поля id в Mongo `_id`. Верхний `@MongoId String id` по-прежнему отображается в `_id`.

Проверки повторных запусков: все восемь Job читают по 15 элементов, исправляют
по 15 при первом запуске и фильтруют все 15 без записей при втором. Тест restart
внедряет сбой после Mongo-записи ID 1108, проверяет checkpoint 908 и чтение пяти
оставшихся документов после продолжения. Все 14 тестов проходят на Java 25.

### Ошибки HTTP-запуска

Все endpoint запуска обрабатывают исключения без `throws Exception`.
Ответ ошибки содержит `jobName`, `code`, `message`; подробности пишутся в лог.
Некорректные параметры дают 400, конфликт состояния Job — 409, внутренняя ошибка
запуска — 500. Неизвестное имя Job возвращает 404. Ошибка уже выполняющегося
Batch отражается в статусе JobExecution и не равнозначна отказу в запуске.
