package Team.demo;

import Team.demo.model.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class QuizController {

    private final AiQuizService aiQuizService;
    private final QuizResultRepository quizResultRepository;
    private final UserRepository userRepository;

    public QuizController(AiQuizService aiQuizService, QuizResultRepository quizResultRepository, UserRepository userRepository) {
        this.aiQuizService = aiQuizService;
        this.quizResultRepository = quizResultRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/")
    public String home(Model model, @RequestParam(value = "error", required = false) String error) {
        if (error != null) {
            model.addAttribute("error", "Failed to generate quiz. The AI service may be busy or the request was invalid. Please try again.");
        }
        return "index";
    }

    @PostMapping("/generate-quiz")
    public String generateQuiz(@RequestParam(required = false) String topic,
                               @RequestParam int numberOfQuestions,
                               @RequestParam String difficulty,
                               @RequestParam String type,
                               @RequestParam int totalTime, // Added totalTime
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
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication.getName();
            // Pass file to AiQuizService
            quiz = aiQuizService.generateQuiz(topic, numberOfQuestions, difficulty, type, file, username);
            if (quiz == null || quiz.getQuestions() == null || quiz.getQuestions().isEmpty()) {
                // Use fallback
                quiz = createFallbackQuiz(topic, difficulty, type);
                model.addAttribute("note", "⚠️ AI service unavailable. Showing a fallback quiz.");
            }
        } catch (Exception e) {
            // Catch exceptions from AI service (e.g., rate limit, bad response)
            e.printStackTrace(); // Log the error
            redirectAttributes.addFlashAttribute("error", "Failed to generate quiz. The AI service may be busy. Please try again.");
            return "redirect:/";
        }

        session.setAttribute("currentQuiz", quiz);
        model.addAttribute("quiz", quiz);
        model.addAttribute("totalTime", totalTime); // Pass totalTime to the quiz page
        return "quiz_dynamic";
    }

    @PostMapping("/submit")
    public String submitQuiz(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return "redirect:/";
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String currentUsername = authentication.getName();
        User currentUser = userRepository.findByUsername(currentUsername).orElse(null);
        Quiz quiz = (Quiz) session.getAttribute("currentQuiz");

        if (quiz == null || currentUser == null) {
            return "redirect:/";
        }

        List<Question> questions = quiz.getQuestions();
        int score = 0;

        QuizResult quizResult = new QuizResult();
        quizResult.setTopic(quiz.getTopic());
        quizResult.setUser(currentUser);

        List<QuestionResult> questionResults = new ArrayList<>();

        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            String userAnswer = request.getParameter("q" + i);
            String correctAnswer = q.getCorrectAnswerText();
            boolean isCorrect = false;

            if (userAnswer != null && !userAnswer.isBlank()) {
                // Case-insensitive check for fill in the blank, exact check for MC
                if (("Fill in the Blank".equals(q.getType()) && userAnswer.equalsIgnoreCase(correctAnswer)) ||
                        (!"Fill in the Blank".equals(q.getType()) && userAnswer.equals(correctAnswer))) {
                    score++;
                    isCorrect = true;
                }
            }

            QuestionResult qr = new QuestionResult();
            qr.setQuestionText(q.getQuestion());
            qr.setUserAnswer(userAnswer != null && !userAnswer.isBlank() ? userAnswer : "Not Answered");
            qr.setCorrectAnswer(correctAnswer);
            qr.setCorrect(isCorrect);
            qr.setExplanation(q.getExplanation()); // <-- Save the explanation
            // We would also save question type and options here if implementing "Retake"
            questionResults.add(qr);
        }

        quizResult.setQuestionResults(questionResults);
        quizResult.setScore(score);
        quizResult.setTotal(questions.size());
        quizResultRepository.save(quizResult);

        model.addAttribute("score", score);
        model.addAttribute("total", questions.size());
        model.addAttribute("questionResults", questionResults);

        session.removeAttribute("currentQuiz");

        return "result";
    }

    @GetMapping("/history")
    public String history(Model model) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String currentUsername = authentication.getName();
        List<QuizResult> history = quizResultRepository.findByUser_UsernameOrderByTimestampDesc(currentUsername);
        model.addAttribute("history", history);
        return "history";
    }

    // ✅ --- NEW PROFILE ENDPOINT --- ✅
    @GetMapping("/profile")
    public String profile(Model model) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String currentUsername = authentication.getName();

        // Fetch all results for statistics
        List<QuizResult> allResults = quizResultRepository.findByUser_UsernameOrderByTimestampDesc(currentUsername);

        // Calculate stats
        int totalQuizzes = allResults.size();
        int totalScore = allResults.stream().mapToInt(QuizResult::getScore).sum();
        int totalQuestions = allResults.stream().mapToInt(QuizResult::getTotal).sum();

        double averageScore = 0.0;
        if (totalQuestions > 0) {
            averageScore = (100.0 * totalScore) / totalQuestions;
        }

        // Get top 5 recent quizzes for display
        List<QuizResult> recentQuizzes = allResults.stream().limit(5).collect(Collectors.toList());

        // Add stats to the model
        model.addAttribute("totalQuizzes", totalQuizzes);
        model.addAttribute("totalQuestions", totalQuestions);
        model.addAttribute("averageScore", averageScore);
        model.addAttribute("recentQuizzes", recentQuizzes);

        return "profile";
    }
    // ==================================


    private Quiz createFallbackQuiz(String topic, String difficulty, String type) {
        List<Question> fallbackQuestions = new ArrayList<>();
        if ("Fill in the Blank".equals(type)) {
            Question q = new Question();
            q.setType("Fill in the Blank");
            q.setQuestion("The capital of France is ____.");
            q.setAnswer("Paris");
            q.setExplanation("Paris is the capital and most populous city of France.");
            fallbackQuestions.add(q);
        } else {
            Question q = new Question();
            q.setType("Multiple Choice");
            q.setQuestion("What does AI stand for?");
            q.setOptions(Arrays.asList("Artificial Intelligence", "Automated Input", "None"));
            q.setCorrectOptionIndex(0);
            q.setExplanation("'AI' is the acronym for 'Artificial Intelligence'.");
            fallbackQuestions.add(q);
        }
        return new Quiz(topic, difficulty, type, fallbackQuestions);
    }
}