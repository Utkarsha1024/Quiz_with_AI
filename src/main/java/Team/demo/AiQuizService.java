package Team.demo;

import Team.demo.model.Question;
import Team.demo.model.Quiz;
import Team.demo.model.QuizResult;
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
import java.util.stream.Collectors;

@Service
public class AiQuizService {

    private static final Logger logger = LoggerFactory.getLogger(AiQuizService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QuizResultRepository quizResultRepository; // For fetching history

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    public AiQuizService(RestTemplate restTemplate, QuizResultRepository quizResultRepository) {
        this.restTemplate = restTemplate;
        this.quizResultRepository = quizResultRepository;
    }

    public Quiz generateQuiz(String topic, String difficulty, String type, MultipartFile file, String username) throws IOException {
        String context;
        try {
            if (file != null && !file.isEmpty()) {
                context = "Based on the document: " + extractTextFromFile(file);
            } else if (topic != null && !topic.isBlank()) {
                context = "Topic: '" + topic + "'";
            } else {
                throw new IllegalArgumentException("A topic or a file must be provided.");
            }
        } catch (NoClassDefFoundError e) {
            logger.error("A required library for file processing is missing. Check pom.xml.", e);
            throw new RuntimeException("Server configuration error for file reading.");
        }

        // ✅ NEW LOGIC: Fetch past questions to avoid repetition
        String exclusionPrompt = "";
        if (topic != null && !topic.isBlank() && username != null) {
            List<QuizResult> pastResults = quizResultRepository.findByUser_UsernameOrderByTimestampDesc(username);
            List<String> pastQuestions = pastResults.stream()
                    .filter(result -> topic.equalsIgnoreCase(result.getTopic()))
                    .flatMap(result -> result.getQuestionResults().stream())
                    .map(qr -> qr.getQuestionText())
                    .distinct()
                    .collect(Collectors.toList());

            if (!pastQuestions.isEmpty()) {
                exclusionPrompt = " CRITICAL INSTRUCTION: Do NOT repeat any of the following questions: " + pastQuestions.toString();
            }
        }

        String prompt = String.format(
                "Generate a 5-question quiz in STRICT JSON format. Do not use markdown. " +
                        "%s, Difficulty: '%s'. " +
                        "%s. " + // This will be the exclusion prompt
                        "The JSON structure MUST be: { \"questions\": [ " +
                        "{ \"question\": \"...\", \"options\": [\"A\", \"B\", \"C\", \"D\"], \"correctOptionIndex\": 0 } ] }. " +
                        "The 'correctOptionIndex' MUST be the 0-based index of the factually correct answer.",
                context, difficulty, exclusionPrompt
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
            int maxLength = 25000;
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
                String rawText = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
                String cleanedJsonText = rawText.replace("```json", "").replace("```", "").trim();
                JsonNode quizJson = objectMapper.readTree(cleanedJsonText);
                List<Question> questions = new ArrayList<>();
                for (JsonNode qNode : quizJson.path("questions")) {
                    questions.add(new Question(qNode.path("question").asText(), jsonNodeToList(qNode.path("options")), qNode.path("correctOptionIndex").asInt()));
                }
                if (questions.isEmpty()) { throw new RuntimeException("AI returned no questions."); }
                return new Quiz(topic, difficulty, type, questions);
            } else { throw new RuntimeException("Gemini API call failed."); }
        } catch (Exception e) { throw new RuntimeException("Error communicating with AI service.", e); }
    }

    private List<String> jsonNodeToList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode element : node) { list.add(element.asText()); }
        }
        return list;
    }
}

