package ru.rsoi.rating.entity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
@Entity
public class AppliedOperation {
    @Id
    private String operationKey;
    protected AppliedOperation() {}
    public AppliedOperation(String key) { this.operationKey = key; }
}
