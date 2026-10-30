package com.lefilmla.back.client;

import com.lefilmla.back.dto.AiCandidate;
import com.lefilmla.back.dto.AiCandidates;
import com.lefilmla.back.exception.UpstreamException;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

@Component
public class ClaudeClient {

	private final ChatClient chatClient;

	public ClaudeClient(ChatClient chatClient) {
		this.chatClient = chatClient;
	}

	public List<AiCandidate> findCandidates(String description) {
		AiCandidates response;
		try {
			// UserMessage plutôt que .user(...) : la description ne doit pas être interprétée comme un template.
			response = chatClient.prompt().messages(new UserMessage(description)).call().entity(AiCandidates.class);
		}
		catch (RuntimeException ex) {
			throw new UpstreamException("Échec de l'appel à Claude : " + ex.getClass().getSimpleName(), ex);
		}
		if (response == null || response.candidates() == null) {
			throw new UpstreamException("Réponse de Claude vide", null);
		}
		return response.candidates();
	}
}
