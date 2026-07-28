package edu.bu.archive.application.port.out;

import edu.bu.archive.domain.model.ai.AiRequest;
import edu.bu.archive.domain.model.ai.AiResponse;

public interface AiProvider {

    String providerName();

    String modelName();

    AiResponse generate(AiRequest request);
}
