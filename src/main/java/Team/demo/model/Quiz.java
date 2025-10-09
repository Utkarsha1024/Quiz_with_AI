package Team.demo.model;

import java.util.List;

public class Quiz {
    private String topic;
    private String difficulty;
    private String type;
    private List<Question> questions;

    public Quiz() {}

    public Quiz(String topic, String difficulty, String type, List<Question> questions) {
        this.topic = topic;
        this.difficulty = difficulty;
        this.type = type;
        this.questions = questions;
    }

    public String getTopic() { return topic; }
    public String getDifficulty() { return difficulty; }
    public String getType() { return type; }
    public List<Question> getQuestions() { return questions; }
}
