package com.scaramutti.tms.workers.service;

import com.scaramutti.tms.shared.repository.DocumentTypeRepository;
import com.scaramutti.tms.workers.dto.DocumentTypeResponse;
import com.scaramutti.tms.workers.mapper.DocumentTypeServiceMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Catalogo de tipos de documento para el formulario de un trabajador. Lectura, sin
 * transaccion propia, como los otros listados de catalogo.
 */
@ApplicationScoped
public class DocumentTypeService {

    @Inject DocumentTypeRepository documentTypeRepository;
    @Inject DocumentTypeServiceMapper documentTypeServiceMapper;

    public List<DocumentTypeResponse> listDocumentTypes() {
        return documentTypeServiceMapper.toDocumentTypeResponseList(
            documentTypeRepository.listActiveOrderedById()
        );
    }
}
