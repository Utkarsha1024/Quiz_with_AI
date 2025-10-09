package Team.demo;

import Team.demo.model.Question;
import Team.demo.model.Quiz;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class AiQuizService {

    private static final Logger logger = LoggerFactory.getLogger(AiQuizService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    public AiQuizService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Quiz generateQuiz(String topic, String difficulty, String type, MultipartFile file) throws IOException {
        String context = "";

        try {
            if (file != null && !file.isEmpty()) {
                context = "Based on the following document content: " + extractTextFromFile(file);
            } else if (topic != null && !topic.isBlank()) {
                context = "Topic: '" + topic + "'";
            } else {
                throw new IllegalArgumentException("A topic or a file must be provided.");
            }
        } catch (NoClassDefFoundError e) {
            // ✅ FIXED: This catches errors if PDF/DOCX libraries are missing from pom.xml
            logger.error("A required library for file processing is missing. Please check pom.xml for Apache POI and PDFBox.", e);
            throw new RuntimeException("Server configuration error: A library for reading files is missing.");
        }


        String prompt = String.format(
                "Generate a quiz with exactly 5 questions in STRICT JSON format only. Do not use markdown. " +
                        "%s, Difficulty: '%s', Type: '%s'. " +
                        "The JSON structure MUST be: { \"questions\": [ " +
                        "{ \"question\": \"...\", \"options\": [\"A\", \"B\", \"C\", \"D\"], \"correctOptionIndex\": 0 } ] }. " +
                        "CRITICAL: The 'correctOptionIndex' MUST be the 0-based index of the factually correct answer in the 'options' array. " +
                        "Ensure the provided answer is accurate for the question asked.",
                context, difficulty, type
        );

        return generateQuizFromPrompt(prompt, topic, difficulty, type);
    }

    private String extractTextFromFile(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        try (InputStream inputStream = file.getInputStream()) {
            String text = "";
            if (fileName != null) {
                if (fileName.toLowerCase().endsWith(".pdf")) {
                    PDDocument document = PDDocument.load(inputStream);
                    PDFTextStripper pdfStripper = new PDFTextStripper();
                    text = pdfStripper.getText(document);
                    document.close();
                } else if (fileName.toLowerCase().endsWith(".docx")) {
                    XWPFDocument doc = new XWPFDocument(inputStream);
                    XWPFWordExtractor extractor = new XWPFWordExtractor(doc);
                    text = extractor.getText();
                    extractor.close();
                } else if (fileName.toLowerCase().endsWith(".txt")) {
                    text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                } else {
                    throw new IOException("Unsupported file type: " + fileName);
                }
            }

            // ✅ FIXED: Prevents API errors by truncating very long documents.
            int maxLength = 30000;
            if (text.length() > maxLength) {
                logger.warn("Document text is too long ({} chars), truncating to {} chars.", text.length(), maxLength);
                return text.substring(0, maxLength);
            }
            return text;
        }
    }

    private Quiz generateQuizFromPrompt(String prompt, String topic, String difficulty, String type) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String requestBody = "{ \"contents\": [{ \"parts\": [{ \"text\": \"" +
                    prompt.replace("\"", "\\\"").replace("\n", "\\n") + "\" }] }] }";

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            String fullApiUrl = geminiApiUrl + "?key=" + geminiApiKey;

            ResponseEntity<String> response = restTemplate.postForEntity(fullApiUrl, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String rawText = root.path("candidates").get(0)
                        .path("content").path("parts").get(0)
                        .path("text").asText();

                logger.debug("Raw Gemini Response: {}", rawText);

                String cleanedJsonText = rawText.replace("```json", "").replace("```", "").trim();

                JsonNode quizJson = objectMapper.readTree(cleanedJsonText);
                List<Question> questions = new ArrayList<>();
                for (JsonNode qNode : quizJson.path("questions")) {
                    String questionText = qNode.path("question").asText();
                    int correctIndex = qNode.path("correctOptionIndex").asInt();

                    List<String> options = new ArrayList<>();
                    for (JsonNode opt : qNode.path("options")) {
                        options.add(opt.asText());
                    }
                    questions.add(new Question(questionText, options, correctIndex));
                }

                if (questions.isEmpty()) {
                    throw new RuntimeException("AI service returned no questions.");
                }

                return new Quiz(topic, difficulty, type, questions);
            } else {
                logger.error("Gemini API call failed with status: {}", response.getStatusCode());
                throw new RuntimeException("Gemini API call failed.");
            }
        } catch (Exception e) {
            logger.error("Error generating quiz for topic '{}'", topic, e);
            throw new RuntimeException("Error communicating with AI service.", e);
        }
    }
}