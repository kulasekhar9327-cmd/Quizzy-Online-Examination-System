
package com.example.online.exam.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "quiz_questions")
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String subject;

    @Column(nullable = false, length = 1000)
    private String questionText;

    @Column(nullable = false, length = 300)
    private String optionA;

    @Column(nullable = false, length = 300)
    private String optionB;

    @Column(nullable = false, length = 300)
    private String optionC;

    @Column(nullable = false, length = 300)
    private String optionD;

    @Column(nullable = false, length = 300)
    private String correctAnswer;

    public Question() {
    }

    public Long getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getOptionA() {
        return optionA;
    }

    public void setOptionA(String optionA) {
        this.optionA = optionA;
    }

    public String getOptionB() {
        return optionB;
    }

    public void setOptionB(String optionB) {
        this.optionB = optionB;
    }

    public String getOptionC() {
        return optionC;
    }

    public void setOptionC(String optionC) {
        this.optionC = optionC;
    }

    public String getOptionD() {
        return optionD;
    }

    public void setOptionD(String optionD) {
        this.optionD = optionD;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(String correctAnswer) {
        this.correctAnswer = correctAnswer;
    }
}
