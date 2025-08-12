package com.trading.repository;

import com.trading.domain.entity.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Position Repository
 */
@Repository
public interface PositionRepository extends JpaRepository<Position, String> {
    Optional<Position> findBySymbolAndAccountId(String symbol, String accountId);
    List<Position> findByAccountId(String accountId);
    List<Position> findBySymbol(String symbol);
}