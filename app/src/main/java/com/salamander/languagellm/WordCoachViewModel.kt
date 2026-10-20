package com.salamander.languagellm

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "LLM_DEBUG"

// Один и тот же файл работает и как имя на HF, и как имя локальной копии —
// меньше шансов перепутать версию модели при обновлении
internal const val HF_REPO_URL = "https://huggingface.co/litert-community/Gemma3-1B-IT"
private const val MODEL_FILE_NAME = "gemma3-1b-it-int4.litertlm"
private const val MODEL_DOWNLOAD_URL = "$HF_REPO_URL/resolve/main/$MODEL_FILE_NAME?download=true"

private const val PREFS_NAME = "hf_auth"
private const val KEY_HF_TOKEN = "hf_token"

// HTTP 401/403 от Hugging Face — отдельный тип, чтобы не путать с сетевой ошибкой:
// в этом случае пользователю нужно менять токен, а не жать "Повторить"
private class AuthException(val code: Int) : Exception("HTTP $code")

private sealed interface TokenCheck {
    data object Valid : TokenCheck
    data class Invalid(val reason: String) : TokenCheck
}

sealed interface CardUiState {
    data object Idle : CardUiState
    data object Loading : CardUiState
    data class Success(
        val card: WordCard,
        // Номер вопроса кладём в сам стейт: если читать ViewModel.currentQuestionNumber,
        // UI показывает уже увеличенное значение ("Question 2 of 5" на первой карточке)
        val displayNumber: Int,
        val shuffledOptions: List<String>,
        val correctIndex: Int
    ) : CardUiState
    data class Error(val message: String) : CardUiState
    data object Results : CardUiState
}

class WordCoachViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    // Ниже этого размера файл считаем битым (недокачанным)
    private val MIN_MODEL_BYTES = 100L * 1024 * 1024

    // 256 токенов впритык: промпт ~150 + ответ — объяснения обрезало на полуслове
    private val MAX_OUTPUT_TOKENS = 512
    private val QUESTIONS_PER_ROUND = 5

    // UI показывает "Question 1 of 5" — число берём отсюда, чтобы не дублировать в ресурсах
    val questionsPerRound: Int get() = QUESTIONS_PER_ROUND

    // ---------- Состояния движка ----------
    var isEngineReady by mutableStateOf(false)
        private set
    var isDownloading by mutableStateOf(false)
        private set
    var downloadProgress by mutableFloatStateOf(0f)
        private set
    var engineError by mutableStateOf<String?>(null)
        private set

    // ---------- Токен Hugging Face ----------
    // Токен вводит сам пользователь: репозиторий gated, а зашивать чужой ключ
    // в сборку нельзя. Лежит в приватных prefs приложения, в код не попадает.
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var hfToken by mutableStateOf(prefs.getString(KEY_HF_TOKEN, "").orEmpty())
        private set
    // Ставится только когда модель реально нужно качать: если файл уже лежит
    // на устройстве, токен не требуется вообще
    var isTokenRequired by mutableStateOf(false)
        private set
    var isCheckingToken by mutableStateOf(false)
        private set
    var tokenError by mutableStateOf<String?>(null)
        private set

    // ---------- Игровые состояния ----------
    var uiState by mutableStateOf<CardUiState>(CardUiState.Idle)
        private set
    var score by mutableIntStateOf(0)
        private set
    var currentQuestionNumber by mutableIntStateOf(1)
        private set
    var finalCardsList by mutableStateOf<List<WordCard>>(emptyList())
        private set
    var userSelectedIndex by mutableStateOf<Int?>(null)
        private set
    var isAiLoading by mutableStateOf(false)
        private set
    val expandedExplanations = mutableStateMapOf<Int, String>()

    // ---------- LiteRT-LM ----------
    // Храним только движок. Сессии (Conversation) одноразовые: создали -> спросили -> закрыли.
    private var litertEngine: Engine? = null
    private val modelPath = File(app.filesDir, MODEL_FILE_NAME).absolutePath

    // Один клиент на загрузку модели: readTimeout = 0, чтобы не рвать
    // соединение на медленном мобильном интернете
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    // Отдельный короткий клиент для проверки токена: ответа ждём секунды, а не вечность
    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // Мьютекс: у CPU-движка нет смысла в параллельных запросах, а isAiLoading
    // привязан к захвату мьютекса, поэтому кнопка рестарта не может нажаться
    // посреди живой JNI-генерации
    private val gemmaMutex = Mutex()

    private val cardGenConfig = ConversationConfig(
        samplerConfig = SamplerConfig(topK = 10, topP = 0.6, temperature = 0.6),
        maxOutputToken = MAX_OUTPUT_TOKENS
    )

    private val tutorConfig = ConversationConfig(
        samplerConfig = SamplerConfig(topK = 20, topP = 0.8, temperature = 0.3),
        maxOutputToken = MAX_OUTPUT_TOKENS
    )

    private var prefetchJob: Deferred<WordCard>? = null
    private var generationJob: Job? = null
    private var explanationJob: Job? = null
    private var downloadJob: Job? = null

    // Счётчик раундов: объяснение, запущенное в прошлом раунде, не должно
    // дописать текст в список нового раунда после restartSession()
    private var roundId = 0

    // История раунда живёт в памяти: cacheDir система чистит когда вздумает,
    // а файловая запись на каждый ответ создавала гонку с чтением результатов
    private val roundCards = mutableListOf<WordCard>()

    // Отдельный скоуп для закрытия движка: переживает onCleared()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val defaultLetters = listOf(
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'L', 'M', 'N', 'O', 'P', 'R', 'S', 'T', 'W'
    )

    // Пул букв тает по мере игры, чтобы слова в раунде не начинались с одной буквы
    private val dynamicLetterPool = mutableListOf<Char>().apply { addAll(defaultLetters) }

    private val categoryImpulses = listOf(
        // 1. Атмосфера и фантастика
        "Cyberpunk, neon, tech",
        "Space, planets, astronomy",
        "Alchemy, magic, castle",
        "Pirates, ships, ocean",
        "Post-apocalypse, wasteland, survival",
        "Steampunk, gears, engines",

        // 2. Экшен и жизнь
        "Extreme sports, speed, adrenaline",
        "Espionage, spies, gadgets",
        "Detectives, investigation, crime",
        "Cooking, food, restaurant",
        "Sculpture, art, painting",
        "Theater, cinema, drama",
        "Surgery, medicine, anatomy",

        // 3. Природа
        "Volcanoes, geology, earth",
        "Jungle, predators, wildlife",
        "Blizzard, arctic, glaciers",
        "Desert, mirages, sand",
        "Thunderstorm, weather, lightning",

        // 4. Психология и эмоции
        "Madness, chaos, mind",
        "Ecstasy, joy, victory",
        "Betrayal, lies, politics",
        "Phobias, horror, panic",

        // 5. Общество
        "Wall Street, finance, money",
        "Street racing, cars, drift",
        "Robotics, artificial intelligence",
        "Aviation, airport, flights"
    )

    private val fallbacks = listOf(
        WordCard("Generous", "Kind", "Selfish"),
        WordCard("Accurate", "Correct", "Wrong"),
        WordCard("Anxious", "Nervous", "Calm"),
        WordCard("Brave", "Courageous", "Cowardly"),
        WordCard("Bright", "Smart", "Stupid")
    )

    init {
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        adoptLegacyModelFile()
        retrySetup()
    }

    /**
     * Раньше файл лежал под локальным псевдонимом gemma3f.litertlm.
     * Переименовываем, чтобы не качать 500 МБ заново после смены имени.
     */
    private fun adoptLegacyModelFile() {
        val legacy = File(app.filesDir, "gemma3f.litertlm")
        val target = File(app.filesDir, MODEL_FILE_NAME)
        if (!legacy.exists() || target.exists()) return
        val moved = legacy.renameTo(target) ||
            runCatching {
                legacy.copyTo(target, overwrite = true)
                legacy.delete()
                true
            }.getOrDefault(false)
        if (moved) Log.d(TAG, "Старая копия модели переименована в $MODEL_FILE_NAME")
    }

    /**
     * Точка входа для первого запуска и для кнопки "Повторить" при ошибке.
     */
    fun retrySetup() {
        if (isEngineReady || isDownloading) return
        engineError = null
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val target = File(app.filesDir, MODEL_FILE_NAME)
            when {
                target.exists() && target.length() > MIN_MODEL_BYTES -> prepareEngine()

                // Токен нужен только чтобы скачать: локальная копия важнее
                hfToken.isBlank() -> isTokenRequired = true

                else -> downloadModel(target)
            }
        }
    }

    // ---------- Токен Hugging Face ----------

    /**
     * Проверяем токен и, если он даёт доступ к файлу, запускаем скачивание.
     * Проверка идёт реальным запросом к файлу модели, а не к /whoami:
     * так мы отличаем "токен битый" (401) от "токен ок, но доступ к gated-репо не выдан" (403).
     */
    fun saveToken(rawToken: String) {
        val token = rawToken.trim()
        if (token.isEmpty()) {
            tokenError = app.getString(R.string.err_token_empty)
            return
        }
        if (isCheckingToken) return

        isCheckingToken = true
        tokenError = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val check = probeModelAccess(token)) {
                    is TokenCheck.Valid -> {
                        prefs.edit().putString(KEY_HF_TOKEN, token).apply()
                        hfToken = token
                        isTokenRequired = false
                        engineError = null
                        retrySetup()
                    }

                    is TokenCheck.Invalid -> tokenError = check.reason
                }
            } finally {
                isCheckingToken = false
            }
        }
    }

    /** Возврат к экрану токена: например, если доступ отозвали или ключ истёк.
     *  Прогресс не сбрасываем: .part остался, с новым токеном докачаем с того же места. */
    fun changeToken() {
        downloadJob?.cancel()
        downloadJob = null
        isDownloading = false
        engineError = null
        tokenError = null
        isTokenRequired = true
    }

    /** Сброс токена из поля: убираем и сохранённый ключ, чтобы он не подхватился снова. */
    fun clearSavedToken() {
        prefs.edit().remove(KEY_HF_TOKEN).apply()
        hfToken = ""
        tokenError = null
    }

    fun reportClipboardEmpty() {
        tokenError = app.getString(R.string.err_clipboard_empty)
    }

    private fun probeModelAccess(token: String): TokenCheck {
        val request = Request.Builder()
            .url(MODEL_DOWNLOAD_URL)
            .addHeader("Authorization", "Bearer $token")
            // Просим один байт: нужен только код ответа, а не полгигабайта весов
            .header("Range", "bytes=0-0")
            .build()
        return try {
            probeClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> TokenCheck.Valid
                    401 == response.code -> TokenCheck.Invalid(authMessage(401))
                    403 == response.code -> TokenCheck.Invalid(authMessage(403))
                    else -> TokenCheck.Invalid(app.getString(R.string.err_http, response.code))
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Проверка токена не удалась: ${e.message}")
            TokenCheck.Invalid(app.getString(R.string.err_network))
        }
    }

    private fun authMessage(code: Int): String = when (code) {
        401 -> app.getString(R.string.err_token_rejected)
        403 -> app.getString(R.string.err_no_repo_access)
        else -> app.getString(R.string.err_http, code)
    }

    private suspend fun downloadModel(target: File) {
        isDownloading = true
        downloadProgress = 0f

        // Качаем во временный файл и только потом переименовываем:
        // если прибить приложение на середине, рядом не останется битой модели.
        // Частичный файл при обрыве НЕ удаляем — следующий прогон докачает
        // через Range с того места, где связь оборвалась
        val tmp = File(app.filesDir, "$MODEL_FILE_NAME.part")
        try {
            val resumeFrom = if (tmp.exists()) tmp.length() else 0L

            val requestBuilder = Request.Builder()
                .url(MODEL_DOWNLOAD_URL)
                .addHeader("Authorization", "Bearer $hfToken")
            if (resumeFrom > 0) {
                requestBuilder.header("Range", "bytes=$resumeFrom-")
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                // 401/403 не лечится повтором — сразу отправляем за новым токеном
                if (response.code == 401 || response.code == 403) {
                    throw AuthException(response.code)
                }

                if (response.code == 416) {
                    // .part длиннее, чем файл на сервере: модель на HF обновилась.
                    // Хвост бесполезен — сносим и качаем заново с нуля
                    Log.w(TAG, "Range выходит за размер файла, качаем с нуля")
                    tmp.delete()
                    downloadProgress = 0f
                    downloadModel(target)
                    return
                }

                // 206 = сервер принял Range и отдаёт остаток (append),
                // 200 = Range проигнорирован — качаем с нуля поверх недокачанного хвоста
                val appending = response.code == 206
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                // body nullable в OkHttp 4.x: пустой ответ — это сетевой сбой, качаем заново
                val body = response.body ?: throw IOException("Пустое тело ответа от сервера")

                val contentLength = body.contentLength()
                // Для 206 contentLength — размер ОСТАТКА; общий прогресс считаем от полного файла
                val totalBytes = if (appending && contentLength > 0) resumeFrom + contentLength else contentLength
                var bytesRead = if (appending) resumeFrom else 0L
                if (totalBytes > 0) {
                    downloadProgress = bytesRead.toFloat() / totalBytes
                }

                // append=true — докачиваем в существующий .part, append=false — пишем с нуля
                FileOutputStream(tmp, appending).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            // Отмена (юзер меняет токен) должна срабатывать между
                            // чанками, а не ждать, пока скачается весь файл
                            if (!currentCoroutineContext().isActive) {
                                throw CancellationException("Загрузка отменена")
                            }
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (totalBytes > 0) {
                                downloadProgress = bytesRead.toFloat() / totalBytes
                            }
                        }
                    }
                }
                if (totalBytes > 0 && bytesRead < totalBytes) {
                    throw Exception(
                        app.getString(
                            R.string.err_connection_lost,
                            (bytesRead / (1024 * 1024)).toInt()
                        )
                    )
                }
            }

            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Log.d(TAG, "Модель скачана: ${target.length()} байт")

            prepareEngine()
        } catch (e: CancellationException) {
            // .part сохраняем: после смены токена загрузка продолжится с того же места
            downloadProgress = 0f
            throw e
        } catch (e: AuthException) {
            Log.e(TAG, "HF отклонил токен: HTTP ${e.code}")
            downloadProgress = 0f
            tokenError = authMessage(e.code)
            isTokenRequired = true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка скачивания модели", e)
            downloadProgress = 0f
            engineError = app.getString(R.string.err_download, e.message.orEmpty())
        } finally {
            isDownloading = false
        }
    }

    private suspend fun prepareEngine() {
        if (litertEngine != null) return
        try {
            // CPU-бэкенд, 4 потока под Helio G88 / Snapdragon 678
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(numOfThreads = 4),
                // Кеш графа/весов: повторные запуски приложения заметно быстрее
                cacheDir = app.cacheDir.absolutePath
            )

            val engine = Engine(config)
            try {
                engine.initialize()
            } catch (e: Exception) {
                runCatching { engine.close() }
                throw e
            }
            litertEngine = engine
            isEngineReady = true
            Log.d(TAG, "Движок LiteRT-LM запущен (CPU, 4 потока)")

            generateNextCard()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации LiteRT-LM", e)
            engineError = app.getString(R.string.err_engine_start, e.message.orEmpty())
        }
    }

    /**
     * Единственная точка общения с моделью. Одноразовая сессия под мьютексом:
     * никаких разделяемых "текущих" сессий, ручных close() и гонок за нативный слот.
     */
    private suspend fun askModel(prompt: String, config: ConversationConfig): String? {
        val engine = litertEngine ?: return null
        return gemmaMutex.withLock {
            try {
                engine.createConversation(config).use { conversation ->
                    conversation.sendMessage(prompt).toString()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Запрос к модели упал: ${e.message}")
                null
            }
        }
    }

    // ---------- Генерация карточек ----------

    // Идеальный точечный промпт под Gemma 3
    private fun buildWordGenerationPrompt(): String {
        val currentCategory = categoryImpulses.random()

        val randomChar = synchronized(dynamicLetterPool) {
            if (dynamicLetterPool.isEmpty()) {
                dynamicLetterPool.addAll(defaultLetters)
            }
            val letter = dynamicLetterPool.random()
            dynamicLetterPool.remove(letter)
            letter.toString()
        }

        return """<bos><start_of_turn>system
You are a strict linguistic API. Based on User data, generate exactly ONE line: MainWord|Synonym|Antonym. Rules: 1. Never output a single letter 2. Synonym MUST be strictly close in meaning to MainWord. 3. Antonym MUST be strictly opposite in meaning to MainWord. 4. End response immediately after Antonym.<end_of_turn>
<start_of_turn>user
[DATA]
CATEGORY: $currentCategory
TARGET_LETTER: $randomChar
[OUTPUT]
<end_of_turn>
<start_of_turn>model
""".trimIndent()
    }

    private fun startPrefetch(): Deferred<WordCard> {
        val usedWords = roundCards.map { it.targetWord.lowercase() }.toSet()
        return viewModelScope.async(Dispatchers.IO) {
            val raw = askModel(buildWordGenerationPrompt(), cardGenConfig)
            val parsed = raw?.let { parseCardResponse(it, usedWords) }
            parsed ?: fallbackCard(usedWords).also {
                Log.w(TAG, "Карточка взята из фолбэка: ${it.targetWord}")
            }
        }
    }

    private fun parseCardResponse(rawResponse: String, usedWords: Set<String>): WordCard? {
        Log.d(TAG, "Сырой ответ модели (генерация): $rawResponse")

        val line = rawResponse.lines().firstOrNull { it.contains("|") }
        val parts: List<String> = if (line != null) {
            line.replace("**", "")
                .replace(Regex("""^\d+\.\s*"""), "")
                .split("|")
                .map { cleanWordPart(it) }
        } else {
            // Модель ответила не по шаблону — пробуем формат "Word: ... / Synonym: ... / Antonym: ..."
            parseFallbackLines(rawResponse)
        }
        return buildCard(parts, usedWords)
    }

    private fun cleanWordPart(part: String): String = part
        .trim()
        .replace("**", "")
        .substringAfterLast(':') // срезаем метки вида "Word:", если модель их налепила
        .trim('.', '!', ',', '"')
        .trim()

    private fun buildCard(parts: List<String>, usedWords: Set<String>): WordCard? {
        val candidate = parts.toMutableList()

        // Страховка: модель иногда выдаёт только "Word|Synonym" — антоним добираем из базы
        if (candidate.size == 2) candidate.add(fallbacks.random().wrongOption)
        if (candidate.size < 3) return null

        val (word, synonym, antonym) = candidate.take(3)
        if (word.isEmpty() || synonym.isEmpty() || antonym.isEmpty()) return null
        if (word.equals(synonym, ignoreCase = true) || word.equals(antonym, ignoreCase = true)) {
            Log.d(TAG, "Модель выдала дубликаты слов, уходим в фолбэк")
            return null
        }
        // Не повторяем слова, которые уже были в этом раунде
        if (word.lowercase() in usedWords) return null

        return WordCard(
            targetWord = word.replaceFirstChar { it.uppercase() },
            correctOption = synonym.replaceFirstChar { it.uppercase() },
            wrongOption = antonym.replaceFirstChar { it.uppercase() }
        )
    }

    private fun parseFallbackLines(rawResponse: String): List<String> {
        val lines = rawResponse.lines().map { it.trim().replace("\"", "").replace("**", "") }
        val target = lines.firstOrNull { it.startsWith("Word:", ignoreCase = true) }?.substringAfter(':')?.trim()
        val synonym = lines.firstOrNull { it.startsWith("Synonym:", ignoreCase = true) }?.substringAfter(':')?.trim()
        val antonym = lines.firstOrNull { it.startsWith("Antonym:", ignoreCase = true) }?.substringAfter(':')?.trim()

        return if (!target.isNullOrEmpty() && !synonym.isNullOrEmpty() && !antonym.isNullOrEmpty()) {
            listOf(target, synonym, antonym)
        } else {
            emptyList()
        }
    }

    private fun fallbackCard(usedWords: Set<String>): WordCard =
        fallbacks.firstOrNull { it.targetWord.lowercase() !in usedWords } ?: fallbacks.random()

    // ---------- Игровой цикл ----------

    fun generateNextCard() {
        if (!isEngineReady) return
        // Защита от двойного тапа по FAB/кнопкам: пока грузим карточку, новые запросы игнорируем
        if (uiState is CardUiState.Loading || uiState is CardUiState.Results) return

        if (currentQuestionNumber > QUESTIONS_PER_ROUND) {
            finalCardsList = roundCards.toList()
            currentQuestionNumber = 1
            uiState = CardUiState.Results
            return
        }

        uiState = CardUiState.Loading
        userSelectedIndex = null

        generationJob = viewModelScope.launch(Dispatchers.Main) {
            val card = try {
                val job = prefetchJob ?: startPrefetch().also { prefetchJob = it }
                job.await()
            } catch (e: Exception) {
                Log.e(TAG, "Не дождались карточку: ${e.message}")
                null
            }
            prefetchJob = null

            // Пока ждали модель, раунд могли перезапустить — не трогаем новый стейт
            if (uiState !is CardUiState.Loading) return@launch

            if (card == null) {
                uiState = CardUiState.Error(app.getString(R.string.err_no_card))
                return@launch
            }

            roundCards.add(card)

            val shuffledOptions = listOf(card.correctOption, card.wrongOption).shuffled()
            val correctIndex = shuffledOptions.indexOf(card.correctOption)
            val displayNumber = currentQuestionNumber
            currentQuestionNumber++

            uiState = CardUiState.Success(card, displayNumber, shuffledOptions, correctIndex)

            // Префетч следующей карточки — пока юзер думает над текущей
            if (displayNumber < QUESTIONS_PER_ROUND) {
                prefetchJob = startPrefetch()
            }
        }
    }

    fun checkAnswer(userSelectedIndex: Int, currentCard: WordCard, correctIndex: Int) {
        // Защита от двойного тапа: второй клик в этом раунде игнорируем
        if (this.userSelectedIndex != null) return
        this.userSelectedIndex = userSelectedIndex
        if (userSelectedIndex == correctIndex) {
            score += 100
        }
    }

    // ---------- Разбор слов на экране результатов ----------

    fun requestExplanationForCard(index: Int, card: WordCard) {
        if (expandedExplanations.containsKey(index)) return
        explanationJob?.cancel()

        expandedExplanations[index] = app.getString(R.string.explanation_loading)
        val myRound = roundId

        explanationJob = viewModelScope.launch(Dispatchers.IO) {
            val engine = litertEngine
            if (engine == null) {
                expandedExplanations[index] = app.getString(R.string.explanation_no_engine)
                return@launch
            }

            gemmaMutex.withLock {
                // Флаг занятости живёт ВНУТРИ мьютекса: он снят ровно тогда,
                // когда движок реально свободен — рестарт в этот момент безопасен
                isAiLoading = true
                try {
                    val raw = engine.createConversation(tutorConfig).use { conversation ->
                        conversation.sendMessage(buildExplanationPrompt(card)).toString()
                    }
                    if (roundId == myRound) {
                        expandedExplanations[index] = cleanExplanation(raw, card)
                    }
                } catch (e: CancellationException) {
                    if (roundId == myRound) expandedExplanations.remove(index)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка объяснения: ${e.message}")
                    if (roundId == myRound) {
                        expandedExplanations[index] = app.getString(R.string.explanation_failed)
                    }
                } finally {
                    isAiLoading = false
                }
            }
        }
    }

    private fun buildExplanationPrompt(card: WordCard): String = """
<bos><start_of_turn>system
You are a strict English tutor. Explain the difference between words. Output exactly 1 or 2 short sentences. No bold, no asterisks, no headers.<end_of_turn>
<start_of_turn>user
Explain why "${card.targetWord}" is similar to "${card.correctOption}", but completely different from "${card.wrongOption}".
[OUTPUT TEMPLATE]
"${card.targetWord}" is close to "${card.correctOption}" because
<end_of_turn>
<start_of_turn>model
"${card.targetWord}" is close to "${card.correctOption}" because
""".trimIndent()

    private fun cleanExplanation(rawResponse: String, card: WordCard): String {
        Log.d(TAG, "Сырой ответ модели (объяснение): $rawResponse")

        var result = rawResponse
            .substringBefore("<")
            .substringBefore("Response:")
            .trim()

        if (result.startsWith("They", ignoreCase = true) || result.startsWith("Both", ignoreCase = true)) {
            result = "${card.targetWord} and ${card.correctOption}: $result"
        } else if (!result.contains(card.targetWord, ignoreCase = true) && !result.startsWith("\"")) {
            // Модель проигнорировала шаблон и начала с середины мысли — достраиваем начало
            result = "${card.targetWord} is close to ${card.correctOption} because $result"
        }
        return result
    }

    // ---------- Рестарт и жизненный цикл ----------

    fun restartSession() {
        roundId++
        generationJob?.cancel()
        explanationJob?.cancel()
        prefetchJob?.cancel()
        prefetchJob = null

        roundCards.clear()
        expandedExplanations.clear()
        userSelectedIndex = null
        currentQuestionNumber = 1
        uiState = CardUiState.Idle

        generateNextCard()
    }

    override fun onCleared() {
        generationJob?.cancel()
        explanationJob?.cancel()
        prefetchJob?.cancel()

        val engine = litertEngine
        litertEngine = null

        if (engine != null) {
            // viewModelScope уже отменён — закрываем движок из своего скоупа.
            // Ждём мьютекс, чтобы не закрыть движок посреди живого JNI-вызова
            engineScope.launch {
                gemmaMutex.withLock {
                    runCatching { engine.close() }
                }
            }
        }
    }
}
