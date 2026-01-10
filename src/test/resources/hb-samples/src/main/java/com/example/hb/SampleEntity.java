package com.example.hb;

import org.hibernate.annotations.Type;
import javax.persistence.Entity;
import javax.persistence.Id;

@TypeDef(name = "jsonb", typeClass = java.lang.Object.class)
@Entity
public class SampleEntity {

    @Id
    private Long id;

    @Type(type = "jsonb")
    private Object data;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}
