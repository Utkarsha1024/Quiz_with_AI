package Team.demo.model;

import java.io.Serializable;
import java.util.List;

public class Question implements Serializable {
    private static final long serialVersionUID = 1L; // Recommended for Serializable classes

    private String type;
    private String question;
    private List<String> options;
    private int correctOptionIndex = -1;
    private String answer;
    private String explanation; // <-- ADDED THIS FIELD

    public Question() {}

    // Getters and Setters
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options; }
    public int getCorrectOptionIndex() { return correctOptionIndex; }
    public void setCorrectOptionIndex(int correctOptionIndex) { this.correctOptionIndex = correctOptionIndex; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    // Getter and Setter for the new field
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public String getCorrectAnswerText() {
        if ("Fill in the Blank".equals(this.type)) {
            return this.answer;
        } else if (options != null && correctOptionIndex >= 0 && correctOptionIndex < options.size()) {
            return options.get(correctOptionIndex);
        }
        return null;
    }
}

