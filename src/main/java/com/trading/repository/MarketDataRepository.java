package com.trading.repository;

import com.trading.domain.entity.MarketData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MarketData Repository
 */
@Repository
public interface MarketDataRepository extends JpaRepository<MarketData, String> {
    
    Optional<MarketData> findTopBySymbolOrderByTimestampDesc(String symbol);
    
    List<MarketData> findBySymbol(String symbol);
    
    List<MarketData> findBySymbolAndTimestampBetween(String symbol, LocalDateTime start, LocalDateTime end);
    
    @Query("SELECT m FROM MarketData m WHERE m.symbol = :symbol AND m.timestamp >= :startTime ORDER BY m.timestamp DESC")
    List<MarketData> findRecentData(@Param("symbol") String symbol, @Param("startTime") LocalDateTime startTime);
    
    @Query("SELECT DISTINCT m.symbol FROM MarketData m")
    List<String> findDistinctSymbols();
}