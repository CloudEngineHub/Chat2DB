package ai.chat2db.community.domain.api.model.ai;

import lombok.Data;

@Data
public class AiContextReferenceSnapshot {

    private String provider;

    private String id;

    private String type;

    private String label;

    private String description;
}
