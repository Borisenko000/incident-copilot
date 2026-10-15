package dev.polina.copilot;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Общая база интеграционных тестов. Spring кэширует контекст по его конфигурации:
 * пока у всех наследников одинаковые аннотации, на весь прогон поднимается
 * один контекст и один контейнер PostgreSQL. Своя аннотация конфигурации в наследнике
 * (другой webEnvironment, @MockitoBean, @TestPropertySource) создаст второй контекст.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {
}
