package com.example.hb;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Convert;
import com.example.hb.converters.JsonbAttributeConverter;

@TypeDef(name = "jsonb", typeClass = java.lang.Object.class)
@Entity
public class SampleEntity {

    @Id
    private Long id;

    @Convert(converter = JsonbAttributeConverter.class)
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
