/**
 * Copyright (c) 2012, Mayocat <hello@mayocat.org>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.mayocat.rest.api.delegate

import com.google.common.base.Optional
import com.google.common.base.Strings
import com.sun.jersey.core.header.FormDataContentDisposition
import com.sun.jersey.multipart.FormDataParam
import groovy.transform.CompileStatic
import org.apache.commons.lang3.RandomStringUtils
import org.apache.commons.lang3.StringUtils
import org.mayocat.Slugifier
import org.mayocat.authorization.annotation.Authorized
import org.mayocat.model.Attachment
import org.mayocat.model.AttachmentData
import org.mayocat.store.AttachmentStore
import org.mayocat.store.EntityAlreadyExistsException
import org.mayocat.store.InvalidEntityException

import javax.inject.Provider
import javax.ws.rs.Consumes
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.WebApplicationException
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

/**
 * Helper class API classes can use to delegate attachment related API operations to.
 *
 * @version $Id$
 */
@CompileStatic
class AttachmentApiDelegate
{
    private Provider<AttachmentStore> attachmentStore;

    private Slugifier slugifier;

    private EntityApiDelegateHandler handler

    private Closure doAfterAttachmentAdded

    @Path("{slug}/attachments")
    @Authorized
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    def addAttachment(@PathParam("slug") String slug,
            @FormDataParam("file") InputStream uploadedInputStream,
            @FormDataParam("file") FormDataContentDisposition fileDetail,
            @FormDataParam("filename") String sentFilename,
            @FormDataParam("title") String title,
            @FormDataParam("description") String description,
            @FormDataParam("target") String target)
    {
        def entity = handler.getEntity(slug);
        if (entity == null) {
            return Response.status(404).build();
        }

        def filename = StringUtils.defaultIfBlank(fileDetail.fileName, sentFilename) as String;
        def created = this.addAttachment(uploadedInputStream, filename, title, description,
                Optional.of(entity.id));

        if (target && created && doAfterAttachmentAdded) {
            doAfterAttachmentAdded.call(target, entity, filename, created)
        }

        if (created) {
            // TODO : 201 created
            return Response.noContent().build();
        }
        else {
            return Response.serverError().build();
        }

    }

    Attachment addAttachment(InputStream data, String originalFilename, String title, String description,
            Optional<UUID> parent)
    {
        if (data == null) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("No file were present\n")
                    .type(MediaType.TEXT_PLAIN_TYPE).build());
        }

        Attachment attachment = new Attachment();

        String fileName;

        if (originalFilename.indexOf(".") > 0) {
            String extension = originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
            attachment.setExtension(extension);
            fileName = StringUtils.removeEnd(originalFilename, "." + extension);
        } else {
            fileName = originalFilename;
        }

        String slug = this.slugifier.slugify(fileName);
        if (Strings.isNullOrEmpty(slug)) {
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity("Invalid file name\n")
                            .type(MediaType.TEXT_PLAIN_TYPE).build());
        }

        attachment.with {
            setSlug slug
            setData new AttachmentData(data)
            setTitle title
            setDescription description
        };

        if (parent.isPresent()) {
            attachment.setParentId(parent.get());
        }

        return this.addAttachment(attachment, 0);
    }

    Attachment addAttachment(Attachment attachment, int recursionLevel)
    {
        if (recursionLevel > 50) {
            // Defensive stack overflow prevention, even though this should not happen
            throw new WebApplicationException(
                    Response.serverError().entity("Failed to create attachment slug").build());
        }
        try {
            try {
                return this.attachmentStore.get().create(attachment);
            } catch (InvalidEntityException e) {
                e.printStackTrace()
                throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                        .entity("Invalid attachment\n")
                        .type(MediaType.TEXT_PLAIN_TYPE).build());
            }
        } catch (EntityAlreadyExistsException e) {
            attachment.slug = attachment.slug + RandomStringUtils.randomAlphanumeric(3);
            return this.addAttachment(attachment, recursionLevel + 1);
        }
    }
}