package com.scaramutti.tms.workers.mapper;

import com.scaramutti.tms.shared.entity.DocumentType;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.workers.dto.DocumentTypeResponse;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * Mapper de la capa Service del catalogo de tipos de documento. El objeto de respuesta es
 * el mismo que va anidado en el detalle de un trabajador: un solo lugar donde cambiarlo.
 */
@Mapper(config = SharedMapperConfig.class)
public interface DocumentTypeServiceMapper {

    DocumentTypeResponse toDocumentTypeResponse(DocumentType documentType);

    List<DocumentTypeResponse> toDocumentTypeResponseList(List<DocumentType> documentTypes);
}
