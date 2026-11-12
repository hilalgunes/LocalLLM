package com.hilal.llm.lab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hilal.llm.navigation.MainDestination

@Composable
fun LaboratoryScreen(running: Boolean = false, onOpen: (MainDestination) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Laboratuvar", style = MaterialTheme.typography.headlineMedium)
            Text("Model ve arama deneylerini aynı uygulama altyapısıyla çalıştır.")
        }
        listOf(
            Triple(MainDestination.EXPERIMENT, "Tekrarlanabilir model deneyi", "Soğuk ve ısıtılmış oturumu ölç; aynı ayarlarla tekrar çalıştır."),
            Triple(MainDestination.RUN, "Model benchmark", "Bir modelin yanıtını ve çalışma sürelerini ölç."),
            Triple(MainDestination.COMPARE, "Model karşılaştırması", "Modelleri ve kayıtlı sonuçları karşılaştır."),
            Triple(MainDestination.EMBEDDING, "Embedding karşılaştırması", "Metin ve görsel arama deneylerini çalıştır."),
            Triple(MainDestination.TEST, "Etkileşim testleri", "Metin, ses ve model davranışlarını dene.")
        ).forEach { (destination, title, detail) ->
            item {
                OutlinedCard(onClick = { onOpen(destination) }, enabled = !running || destination == MainDestination.EXPERIMENT, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(detail, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
