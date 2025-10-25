package org.lime.chatbotwithai.product;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name="product")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Product {
    @Id
    private Long id;
    private String brand;     // Bosch, Samsung...
    private String model;     // Model name
    private String type;      // front | top
    private Double price;     // Price in EUR
    @Column(name="capacity_kg")
    private Integer capacityKg;
    @Column(name="width_cm")
    private Double widthCm;
    @Column(name="height_cm")
    private Double heightCm;
    @Column(name="depth_cm")
    private Double depthCm;
    @Column(length=1000)
    private String description;
}
