package ai.chat2db.community.domain.api.model.request.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiContextReferenceRequest {

    @NotBlank
    @Size(max = 64)
    private String provider;

    @NotBlank
    @Size(max = 128)
    private String id;

    @NotBlank
    @Size(max = 64)
    private String type;
}
