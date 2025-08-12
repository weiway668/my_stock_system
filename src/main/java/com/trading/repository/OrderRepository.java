package com.trading.repository;

import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Order Repository
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    
    Optional<Order> findByOrderId(String orderId);
    
    List<Order> findByAccountId(String accountId);
    
    List<Order> findByAccountIdAndStatus(String accountId, OrderStatus status);
    
    List<Order> findBySymbol(String symbol);
    
    List<Order> findByStatus(OrderStatus status);
    
    @Query("SELECT o FROM Order o WHERE o.createdAt >= :startTime AND o.createdAt <= :endTime")
    List<Order> findOrdersBetweenDates(@Param("startTime") LocalDateTime startTime, 
                                      @Param("endTime") LocalDateTime endTime);
    
    @Query("SELECT o FROM Order o WHERE o.accountId = :accountId AND o.status IN :statuses")
    List<Order> findByAccountIdAndStatusIn(@Param("accountId") String accountId, 
                                          @Param("statuses") List<OrderStatus> statuses);
    
    @Query("SELECT o FROM Order o WHERE o.createdAt >= :startTime ORDER BY o.createdAt DESC")
    List<Order> findRecentOrders(@Param("startTime") LocalDateTime startTime);
    
    @Query("SELECT DISTINCT o.symbol FROM Order o WHERE o.createdAt >= :startTime")
    List<String> findActiveSymbols(@Param("startTime") LocalDateTime startTime);
    
    List<Order> findByAccountIdAndCreateTimeAfter(String accountId, LocalDateTime createTime);
}