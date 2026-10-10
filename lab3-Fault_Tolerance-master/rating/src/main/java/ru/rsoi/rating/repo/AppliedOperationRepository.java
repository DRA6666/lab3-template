package ru.rsoi.rating.repo;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.rsoi.rating.entity.AppliedOperation;
public interface AppliedOperationRepository extends JpaRepository<AppliedOperation, String> {}
