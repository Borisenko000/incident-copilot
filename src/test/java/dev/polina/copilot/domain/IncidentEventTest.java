package dev.polina.copilot.domain;

import static dev.polina.copilot.domain.TestEvents.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IncidentEventTest {

    @Test
    @DisplayName("пустой обязательный идентификатор отклоняется")
    void rejectsBlankRequiredField() {
        assertThatThrownBy(() -> event().eventId("  ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    @DisplayName("событие без severity отклоняется")
    void rejectsMissingSeverity() {
        assertThatThrownBy(() -> event().severity(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("severity");
    }

    @Test
    @DisplayName("словари копируются: изменение исходного Map не меняет событие, а сам словарь неизменяем")
    void copiesMapsDefensively() {
        Map<String, Object> source = new HashMap<>(Map.of("topic", "orders"));
        IncidentEvent event = event().attributes(source).build();

        source.put("topic", "changed");

        assertThat(event.attributes()).containsEntry("topic", "orders");
        assertThatThrownBy(() -> event.attributes().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("отсутствующие словари превращаются в пустые")
    void nullMapsBecomeEmpty() {
        IncidentEvent event = event().correlation(null).attributes(null).technology(null).build();

        assertThat(event.correlation()).isEmpty();
        assertThat(event.attributes()).isEmpty();
        assertThat(event.technology()).isEmpty();
    }
}
