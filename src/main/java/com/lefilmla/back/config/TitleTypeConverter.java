package com.lefilmla.back.config;

import com.lefilmla.back.dto.TitleType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class TitleTypeConverter implements Converter<String, TitleType> {

	@Override
	public TitleType convert(String source) {
		return TitleType.fromTmdbName(source);
	}
}
