package ru.rsoi.library.repo;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.rsoi.library.entity.AppliedOperation;
public interface AppliedOperationRepository extends JpaRepository<AppliedOperation, String> {}
