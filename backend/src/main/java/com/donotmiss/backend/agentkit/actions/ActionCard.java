package com.donotmiss.backend.agentkit.actions;

import java.util.List;

/**
 * What a confirmation card shows (F2 decision 4). Built by the tool from the database and the
 * frozen arguments, never from the model's own wording, so the card shows what will really happen.
 *
 * @param kind     what the action is, e.g. "预约活动"
 * @param headline the thing it acts on, e.g. the event title
 * @param fields   the key facts, e.g. time and place; for a change, "旧 → 新"
 * @param effects  what else happens, e.g. "会自动加进你的日程表"
 * @param warnings things worth a second look that do not block it, e.g. a time clash
 * @param link     the page where the result can be seen and changed by hand
 */
public record ActionCard(String kind, String headline, List<Field> fields, List<String> effects,
                         List<String> warnings, String link) {
    public record Field(String label, String value) {
    }

    public ActionCard {
        fields = fields == null ? List.of() : List.copyOf(fields);
        effects = effects == null ? List.of() : List.copyOf(effects);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
