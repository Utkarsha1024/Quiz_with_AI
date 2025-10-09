package Team.demo.model;

import java.util.List;

public class Question {
    // The controller passes null for the ID in the fallback, so this field is included for compatibility.
    private Long id;
    private String question;
    private List<String> options;

    // This field replaces the old 'String correctAnswer'
    private int correctOptionIndex;

    public Question() {}

    // Constructor used by the fallback method in QuizController
    public Question(Long id, String question, List<String> options, int correctOptionIndex) {
        this.id = id;
        this.question = question;
        this.options = options;
        this.correctOptionIndex = correctOptionIndex;
    }

    // An alternative constructor that might be useful elsewhere
    public Question(String question, List<String> options, int correctOptionIndex) {
        this.question = question;
        this.options = options;
        this.correctOptionIndex = correctOptionIndex;
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options; }

    public int getCorrectOptionIndex() {
        return correctOptionIndex;
    }

    public void setCorrectOptionIndex(int correctOptionIndex) {
        this.correctOptionIndex = correctOptionIndex;
    }

    /**
     * ✅ FIX: This is the missing helper method that the results page needs.
     * It safely gets the text of the correct answer using the index.
     * @return The correct answer string, or null if not available.
     */
    public String getCorrectAnswerText() {
        if (options != null && correctOptionIndex >= 0 && correctOptionIndex < options.size()) {
            return options.get(correctOptionIndex);
        }
        return null;
    }
}

