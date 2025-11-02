package Team.demo;

import Team.demo.model.Question;
import Team.demo.model.Quiz;
import Team.demo.model.QuizResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader; // <-- ADD THIS IMPORT
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument; // <-- ADD THIS IMPORT
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile; // Restored import

import java.io.IOException;
import java.io.InputStream; // Restored import
import java.nio.charset.StandardCharsets; // Restored import
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AiQuizService {

    private static final Logger logger = LoggerFactory.getLogger(AiQuizService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QuizResultRepository quizResultRepository;

    @Value("${gemini.api.key}")
    private String geminiApiKey;
    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    public AiQuizService(RestTemplate restTemplate, QuizResultRepository quizResultRepository) {
        this.restTemplate = restTemplate;
        this.quizResultRepository = quizResultRepository;
    }

    // Restored original method signature
    public Quiz generateQuiz(String topic, int numberOfQuestions, String difficulty, String type, MultipartFile file, String username) throws IOException {
        // Restored original context logic
        String context;
        if (file != null && !file.isEmpty()) {
            context = "Based on the provided document: " + extractTextFromFile(file);
        } else {
            context = "on the topic of '" + topic + "'";
        }

        String exclusionPrompt = "";
        if (topic != null && !topic.isBlank() && username != null) {
            List<String> pastQuestions = quizResultRepository.findByUser_UsernameOrderByTimestampDesc(username).stream()
                    .filter(result -> topic.equalsIgnoreCase(result.getTopic()))
                    .flatMap(result -> result.getQuestionResults().stream().map(qr -> qr.getQuestionText()))
                    .distinct().collect(Collectors.toList());
            if (!pastQuestions.isEmpty()) {
                exclusionPrompt = " CRITICAL: Do NOT repeat any of the following questions: " + pastQuestions.toString();
            }
        }

        // ✅ REFINED: Create completely separate, more forceful prompts for each type.
        String prompt;
        if ("Fill in the Blank".equals(type)) {
            prompt = String.format(
                    "Generate a quiz with exactly %d 'Fill in the Blank' questions. The quiz is %s. Difficulty: '%s'. " +
                            "IMPORTANT: You must only output a JSON object. Do not add any other text or markdown. " +
                            "Follow this exact JSON structure: " +
                            "{\"questions\":[{\"question\":\"A question with a ____ in it.\",\"answer\":\"The missing word\"}]}. " +
                            "The 'question' field MUST contain '____' as the blank. %s",
                    numberOfQuestions, context, difficulty, exclusionPrompt
            );
        } else { // Default to Multiple Choice
            prompt = String.format(
                    "Generate a quiz with exactly %d 'Multiple Choice' questions. The quiz is %s. Difficulty: '%s'. " +
                            "Output ONLY a strict JSON object (no markdown). " +
                            "The JSON structure MUST be: {\"questions\":[{\"question\":\"...\",\"options\":[\"A\",\"B\",\"C\",\"D\"],\"correctOptionIndex\":0}]}. " +
                            "The 'correctOptionIndex' MUST be the 0-based index of the correct answer. %s",
                    numberOfQuestions, context, difficulty, exclusionPrompt
            );
        }

        return generateQuizFromPrompt(prompt, topic, difficulty, type);
    }

    // Restored the extractTextFromFile method
    private String extractTextFromFile(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        try (InputStream inputStream = file.getInputStream()) {
            String text = "";
            if (fileName != null) {
                if (fileName.toLowerCase().endsWith(".pdf")) {
                    // Read bytes from the input stream
                    byte[] bytes = inputStream.readAllBytes();
                    // Now load the PDF from the byte array
                    PDDocument document = Loader.loadPDF(bytes);
                    text = new PDFTextStripper().getText(document);
                    document.close();
                } else if (fileName.toLowerCase().endsWith(".docx")) {
                    text = new XWPFWordExtractor(new XWPFDocument(inputStream)).getText();
                } else if (fileName.toLowerCase().endsWith(".txt")) {
                    text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                } else {
                    throw new IOException("Unsupported file type: " + fileName);
                }
            }
            int maxLength = 25000; // Limit context size
            return text.length() > maxLength ? text.substring(0, maxLength) : text;
        }
    }

    private Quiz generateQuizFromPrompt(String prompt, String topic, String difficulty, String type) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String requestBody = "{ \"contents\": [{ \"parts\": [{ \"text\": \"" + prompt.replace("\"", "\\\"").replace("\n", "\\n") + "\" }] }] }";
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            String fullApiUrl = geminiApiUrl + "?key=" + geminiApiKey;

            ResponseEntity<String> response = restTemplate.postForEntity(fullApiUrl, entity, String.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                if (!root.has("candidates") || !root.get("candidates").isArray() || root.get("candidates").isEmpty()) {
                    // Check for a promptFeedback block which might indicate a safety block
                    if (root.has("promptFeedback") && root.path("promptFeedback").has("blockReason")) {
                        String reason = root.path("promptFeedback").path("blockReason").asText();
                        logger.warn("AI prompt was blocked. Reason: {}", reason);
                        throw new RuntimeException("The request was blocked by the AI for safety reasons: " + reason);
                    }
                    throw new RuntimeException("AI response was empty or invalid.");
                }

                String rawText = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
// ... existing code ... // Log the raw response for debugging
                String cleanedJsonText = rawText.replace("```json", "").replace("```", "").trim();

                if (!cleanedJsonText.startsWith("{") || !cleanedJsonText.endsWith("}")) {
                    logger.error("AI did not return a valid JSON object. Raw text: {}", cleanedJsonText);
                    throw new IOException("AI did not return a valid JSON object.");
                }

                JsonNode quizJson = objectMapper.readTree(cleanedJsonText);
                List<Question> questions = new ArrayList<>();

                if (!quizJson.has("questions") || !quizJson.get("questions").isArray()) {
                    logger.error("AI response JSON is missing 'questions' array. JSON: {}", cleanedJsonText);
                    throw new IOException("AI response JSON is malformed.");
                }

                for (JsonNode qNode : quizJson.path("questions")) {
                    if (!qNode.hasNonNull("question")) continue;

                    Question question = new Question();
                    question.setType(type);
                    question.setQuestion(qNode.path("question").asText());

                    if ("Fill in the Blank".equals(type)) {
                        if (!qNode.hasNonNull("answer")) {
                            logger.warn("Skipping Fill-in-the-Blank question due to missing 'answer' field: {}", qNode.toString());
                            continue;
                        }
                        question.setAnswer(qNode.path("answer").asText());
                    } else {
                        if (!qNode.hasNonNull("options") || !qNode.get("options").isArray() || qNode.get("options").isEmpty() || !qNode.hasNonNull("correctOptionIndex")) {
                            logger.warn("Skipping Multiple Choice question due to missing fields: {}", qNode.toString());
                            continue;
                        }
                        List<String> options = new ArrayList<>();
                        for (JsonNode opt : qNode.path("options")) {
                            options.add(opt.asText());
                        }
                        question.setOptions(options);
                        question.setCorrectOptionIndex(qNode.path("correctOptionIndex").asInt());
                    }
                    questions.add(question);
                }

                if(questions.isEmpty()){
                    logger.error("Failed to parse any valid questions from AI response. JSON: {}", cleanedJsonText);
                    throw new RuntimeException("Failed to parse any questions from AI response.");
                }
                return new Quiz(topic, difficulty, type, questions);
            } else { throw new RuntimeException("Gemini API call failed."); }
        } catch (Exception e) {
            logger.error("Error during AI quiz generation or parsing.", e);
            throw new RuntimeException("Error communicating with or parsing response from AI service.", e);
        }
    }
}