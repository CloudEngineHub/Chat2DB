package ai.chat2db.community.domain.api.service.ai;

import ai.chat2db.community.domain.api.model.ai.AiBusinessContextResult;
import ai.chat2db.community.domain.api.model.request.ai.AiBusinessContextBuildRequest;

/**
 * Builds structured business context for AI requests.
 */
public interface IAiBusinessContextService {

    /**
     * Resolves untrusted context references and builds structured AI business context.
     *
     * @param aiBusinessContextBuildRequest AI business context build parameters.
     * @return server-resolved context and trusted reference snapshots.
     */
    AiBusinessContextResult resolve(AiBusinessContextBuildRequest aiBusinessContextBuildRequest);
}
