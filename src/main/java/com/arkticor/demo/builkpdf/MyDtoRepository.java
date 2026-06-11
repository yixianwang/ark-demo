package com.arkticor.demo.builkpdf;

import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Demo stand-in for your real data access. In production this should be ONE batched query (WHERE id
 * IN (...)) that preserves the requested id order.
 */
@Repository
public class MyDtoRepository {

  public List<MyDto> findAllByIdInOrder(List<String> ids) {
    return ids.stream()
        .map(
            id ->
                new MyDto(
                    id,
                    "Customer " + id,
                    "ACC-" + id,
                    "$%,.2f".formatted(Math.abs(id.hashCode()) % 100_000 / 100.0),
                    id.hashCode() % 2 == 0))
        .toList();
  }
}
