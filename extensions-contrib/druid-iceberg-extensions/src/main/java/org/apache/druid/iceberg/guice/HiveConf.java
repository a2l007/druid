package org.apache.druid.iceberg.guice;

import com.google.inject.BindingAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Each extension module needs to properly bind whatever it will use, but sometimes different modules need to bind the
 * same class which will lead to the duplicate injection error. To avoid this problem, each module is supposed to bind
 * different instances.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@BindingAnnotation
public @interface HiveConf
{
}
