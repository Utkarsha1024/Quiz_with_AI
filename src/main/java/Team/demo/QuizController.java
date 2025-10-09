package Team.demo;

import Team.demo.model.Question;
import Team.demo.model.Quiz;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Controller
public class QuizController {

    private final AiQuizService aiQuizService;

    public QuizController(AiQuizService aiQuizService) {
        this.aiQuizService = aiQuizService;
    }

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @PostMapping("/generate-quiz")
    public String generateQuiz(@RequestParam(required = false) String topic,
                               @RequestParam String difficulty,
                               @RequestParam String type,
                               @RequestParam(required = false) MultipartFile file,
                               Model model,
                               HttpSession session,
                               RedirectAttributes redirectAttributes) {

        if ((topic == null || topic.isBlank()) && (file == null || file.isEmpty())) {
            redirectAttributes.addFlashAttribute("error", "Please provide a topic or upload a file.");
            return "redirect:/";
        }

        Quiz quiz;
        try {
            quiz = aiQuizService.generateQuiz(topic, difficulty, type, file);
            if (quiz == null || quiz.getQuestions() == null || quiz.getQuestions().isEmpty()) {
                quiz = createFallbackQuiz(topic, difficulty, type);
                model.addAttribute("note", "⚠️ AI service unavailable. Showing a fallback quiz.");
            }
        } catch (Exception e) {
            quiz = createFallbackQuiz(topic, difficulty, type);
            model.addAttribute("note", "⚠️ An error occurred while generating the quiz. Showing a fallback quiz.");
        }

        session.setAttribute("currentQuiz", quiz);
        model.addAttribute("quiz", quiz);

        // ✅ FIX: This now correctly sends the user to the quiz display page.
        return "quiz_dynamic";
    }

    @PostMapping("/submit")
    public String submitQuiz(HttpServletRequest request, Model model, HttpSession session) {
        Quiz quiz = (Quiz) session.getAttribute("currentQuiz");
        if (quiz == null) {
            return "redirect:/"; // No quiz in session, go home.
        }

        List<Question> questions = quiz.getQuestions();
        List<String> userAnswers = new ArrayList<>();
        int score = 0;

        for (int i = 0; i < questions.size(); i++) {
            String userAnswer = request.getParameter("q" + i);
            if (userAnswer == null) {
                userAnswers.add("Not Answered");
            } else {
                userAnswers.add(userAnswer);
                String correctAnswer = questions.get(i).getCorrectAnswerText();
                if (userAnswer.equals(correctAnswer)) {
                    score++;
                }
            }
        }

        model.addAttribute("score", score);
        model.addAttribute("total", questions.size());
        model.addAttribute("questions", questions);
        model.addAttribute("userAnswers", userAnswers);

        return "result";
    }

    private Quiz createFallbackQuiz(String topic, String difficulty, String type) {
        List<Question> fallbackQuestions = new ArrayList<>();
        fallbackQuestions.add(new Question("What does AI stand for?",
                Arrays.asList("Artificial Intelligence", "Automated Input", "Advanced Integration", "None"), 0));
        fallbackQuestions.add(new Question("Which language is most popular for AI development?",
                Arrays.asList("Python", "Java", "C++", "Ruby"), 0));
        return new Quiz(topic, difficulty, type, fallbackQuestions);
    }
}