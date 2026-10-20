package com.salamander.languagellm

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Все пользовательские тексты живут в res/values*: английский — базовый,
// русский подхватывается сам на устройствах с русской локалью
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordCoachScreen(viewModel: WordCoachViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Word Coach", fontWeight = FontWeight.Bold) },
                actions = {
                    Text(
                        text = stringResource(R.string.score, viewModel.score),
                        modifier = Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                // Нет токена или HF его не принял — сначала авторизация
                viewModel.isTokenRequired -> TokenGateContent(viewModel)

                // Ошибка на этапе скачивания/запуска модели — даём повторить
                viewModel.engineError != null -> EngineErrorContent(viewModel)

                // Первое скачивание модели (~500 МБ)
                viewModel.isDownloading -> DownloadContent(viewModel)

                // Модель на месте, но движок ещё инициализируется
                !viewModel.isEngineReady -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.engine_starting))
                }

                else -> when (val state = viewModel.uiState) {
                    CardUiState.Idle -> Button(onClick = { viewModel.generateNextCard() }) {
                        Text(stringResource(R.string.start_game))
                    }

                    CardUiState.Loading -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.card_generating))
                    }

                    is CardUiState.Success -> QuestionContent(viewModel, state)

                    is CardUiState.Error -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.error_with_message, state.message),
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.generateNextCard() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }

                    CardUiState.Results -> ResultsContent(viewModel)
                }
            }
        }
    }
}

@Composable
private fun EngineErrorContent(viewModel: WordCoachViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = viewModel.engineError.orEmpty(),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { viewModel.retrySetup() }) {
            Text(stringResource(R.string.retry))
        }
        TextButton(onClick = { viewModel.changeToken() }) {
            Text(stringResource(R.string.change_token))
        }
    }
}

/**
 * Минималистичный экран авторизации: свой токен HF + ссылка на gated-репозиторий,
 * чтобы пользователь мог сразу выдать себе доступ и вернуться.
 */
@Composable
private fun TokenGateContent(viewModel: WordCoachViewModel) {
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf(viewModel.hfToken) }
    var isVisible by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.token_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.token_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.token_label)) },
            placeholder = { Text(stringResource(R.string.token_placeholder)) },
            visualTransformation = if (isVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None
            ),
            trailingIcon = {
                // Три действия живут прямо в поле: вставить, показать/скрыть, сбросить
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            val pasted = clipboard?.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                ?.let(::sanitizeToken)

                            if (pasted.isNullOrEmpty()) {
                                viewModel.reportClipboardEmpty()
                            } else {
                                draft = pasted
                            }
                        },
                        enabled = !viewModel.isCheckingToken
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_paste),
                            contentDescription = stringResource(R.string.token_paste),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { isVisible = !isVisible },
                        enabled = !viewModel.isCheckingToken
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isVisible) R.drawable.ic_visibility_off
                                else R.drawable.ic_visibility
                            ),
                            contentDescription = stringResource(
                                if (isVisible) R.string.token_hide else R.string.token_show
                            ),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (draft.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                draft = ""
                                viewModel.clearSavedToken()
                            },
                            enabled = !viewModel.isCheckingToken
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.token_clear),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            isError = viewModel.tokenError != null,
            enabled = !viewModel.isCheckingToken && !viewModel.isDownloading
        )

        viewModel.tokenError?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.saveToken(draft) },
            enabled = !viewModel.isCheckingToken && draft.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (viewModel.isCheckingToken) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.token_save_and_download))
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        RepoLinkPill(onClick = { openRepo(context) })

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.token_repo_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Круглая pill-плашка со ссылкой на репозиторий модели. */
@Composable
private fun RepoLinkPill(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.token_repo_pill),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
            Text(text = "↗", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun openRepo(context: Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HF_REPO_URL)))
    }
}

/**
 * Из буфера часто прилетает не только сам ключ: перевод строки, лишние пробелы,
 * иногда скопированный заголовок "Bearer ...". Оставляем только токен.
 */
private fun sanitizeToken(raw: String): String = raw
    .trim()
    .removePrefix("Bearer ")
    .removePrefix("bearer ")
    .trim()

@Composable
private fun DownloadContent(viewModel: WordCoachViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (viewModel.downloadProgress > 0f) {
            Text(
                stringResource(
                    R.string.download_progress,
                    (viewModel.downloadProgress * 100).toInt()
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(progress = { viewModel.downloadProgress })
        } else {
            // Размер файла сервер не сообщил — показываем неопределённый прогресс
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.download_indeterminate))
        }
    }
}

@Composable
private fun QuestionContent(viewModel: WordCoachViewModel, state: CardUiState.Success) {
    val hasAnswered = viewModel.userSelectedIndex != null

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
        ) {
            Text(
                text = stringResource(
                    R.string.question_counter,
                    state.displayNumber,
                    viewModel.questionsPerRound
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.question_prompt, state.card.targetWord),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                state.shuffledOptions.forEachIndexed { index, optionText ->
                    val containerColor = when {
                        !hasAnswered -> MaterialTheme.colorScheme.surfaceVariant
                        index == state.correctIndex -> Color(0xFF4CAF50)
                        index == viewModel.userSelectedIndex -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                    val contentColor = if (!hasAnswered) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        Color.White
                    }

                    Button(
                        onClick = {
                            if (!hasAnswered) {
                                viewModel.checkAnswer(
                                    userSelectedIndex = index,
                                    currentCard = state.card,
                                    correctIndex = state.correctIndex
                                )
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(120.dp),
                        shape = RoundedCornerShape(24.dp),
                        enabled = !hasAnswered,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = containerColor,
                            contentColor = contentColor,
                            disabledContainerColor = containerColor,
                            disabledContentColor = contentColor
                        )
                    ) {
                        Text(
                            text = optionText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Круглая кнопка-галочка: всплывает снизу после выбора ответа
        AnimatedVisibility(
            visible = hasAnswered,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            FloatingActionButton(
                onClick = { viewModel.generateNextCard() },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(8.dp),
                modifier = Modifier.size(64.dp)
            ) {
                Text(text = "✔️", fontSize = 24.sp)
            }
        }
    }
}

@Composable
private fun ResultsContent(viewModel: WordCoachViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.round_completed),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(
            text = stringResource(R.string.word_interpretations),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            itemsIndexed(
                viewModel.finalCardsList,
                // key нужен, чтобы при новом раунде Compose не переиспользовал
                // старое состояние карточек (раскрытость, текст разбора)
                key = { _, card -> card.targetWord }
            ) { index, card ->
                ResultCard(viewModel, index, card)
            }
        }

        Button(
            onClick = { viewModel.restartSession() },
            // isAiLoading снимается только при реальном освобождении движка,
            // поэтому кнопка физически не нажмётся посреди генерации
            enabled = !viewModel.isAiLoading,
            modifier = Modifier.padding(top = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (viewModel.isAiLoading) Color.Gray else MaterialTheme.colorScheme.primary,
                disabledContainerColor = Color.Gray
            )
        ) {
            Text(
                stringResource(
                    if (viewModel.isAiLoading) R.string.ai_thinking else R.string.restart
                )
            )
        }
    }
}

@Composable
private fun ResultCard(viewModel: WordCoachViewModel, index: Int, card: WordCard) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                isExpanded = !isExpanded
                if (isExpanded) {
                    viewModel.requestExplanationForCard(index, card)
                }
            },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.result_word_similar,
                        card.targetWord.uppercase()
                    ),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(text = if (isExpanded) "▲" else "▼")
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                    Text(
                        text = stringResource(R.string.result_correct, card.correctOption),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.result_wrong, card.wrongOption),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Text(
                        text = stringResource(R.string.learn_why),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = viewModel.expandedExplanations[index]
                            ?: stringResource(R.string.explanation_tap_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
