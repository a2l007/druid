package org.apache.druid.data.input;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.druid.data.input.impl.LocalInputSourceAdapter;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = InputSource.TYPE_PROPERTY)
@JsonSubTypes(value = {
    @JsonSubTypes.Type(name = LocalInputSourceAdapter.TYPE_KEY, value = LocalInputSourceAdapter.class)
})
public interface InputSourceAdapter<T>
{
  InputSource generateInputSource(List<T> inputFilePaths);
}
