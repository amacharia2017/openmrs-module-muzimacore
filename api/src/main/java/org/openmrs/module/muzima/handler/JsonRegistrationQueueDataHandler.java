/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 * <p>
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 * <p>
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.muzima.handler;

import com.jayway.jsonpath.Criteria;
import com.jayway.jsonpath.Filter;
import com.jayway.jsonpath.JsonPath;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.javarosa.core.services.Logger;
import org.openmrs.PersonAddress;
import org.openmrs.annotation.Handler;
import com.jayway.jsonpath.InvalidPathException;
import org.openmrs.api.PersonService;
import org.openmrs.api.context.Context;
import org.openmrs.PersonName;
import org.openmrs.Patient;
import org.openmrs.PersonAttribute;
import org.openmrs.module.muzima.api.service.RegistrationDataService;
import org.openmrs.module.muzima.exception.QueueProcessorException;
import org.openmrs.module.muzima.model.QueueData;
import org.openmrs.module.muzima.model.RegistrationData;
import org.openmrs.module.muzima.model.handler.QueueDataHandler;
import org.openmrs.module.muzima.utils.JsonUtils;
import org.openmrs.PatientIdentifier;
import org.openmrs.Location;
import org.openmrs.PersonAttributeType;
import org.openmrs.PatientIdentifierType;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * TODO: Write brief description about the class here.
 */
@Handler(supports = QueueData.class, order = 1)
public class JsonRegistrationQueueDataHandler implements QueueDataHandler {

    private static final String DISCRIMINATOR_VALUE = "json-registration";

    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    private final Log log = LogFactory.getLog(JsonRegistrationQueueDataHandler.class);

    private Patient unsavedPatient;
    private String payload;
    Set<PersonAttribute> personAttributes;
    private QueueProcessorException queueProcessorException;

    @Override
    public void process(final QueueData queueData) throws QueueProcessorException {
        log.info("Processing registration form data: " + queueData.getUuid());
        queueProcessorException = new QueueProcessorException();
        try {
            if (validate(queueData)) {
                registerUnsavedPatient();
            }
        } catch (Exception e) {
            /*Custom exception thrown by the validate function should not be added again into @queueProcessorException.
             It should add the runtime dao Exception while saving the data into @queueProcessorException collection */
            if (!e.getClass().equals(QueueProcessorException.class)) {
                queueProcessorException.addException(e);
            }
        } finally {
            if (queueProcessorException.anyExceptions()) {
                throw queueProcessorException;
            }
        }
    }

    @Override
    public boolean validate(QueueData queueData) {
        log.info("Processing registration form data: " + queueData.getUuid());
        queueProcessorException = new QueueProcessorException();
        try {
            payload = queueData.getPayload();
            unsavedPatient = new Patient();
            populateUnsavedPatientFromPayload();
            validateUnsavedPatient();
            return true;
        } catch (Exception e) {
            queueProcessorException.addException(e);
            return false;
        } finally {
            if (queueProcessorException.anyExceptions()) {
                throw queueProcessorException;
            }
        }
    }

    @Override
    public String getDiscriminator() {
        return DISCRIMINATOR_VALUE;
    }

    private void validateUnsavedPatient() {
        Patient savedPatient = findSimilarSavedPatient();
        if (savedPatient != null) {
            queueProcessorException.addException(
                    new Exception(
                            "Found a patient with similar characteristic :  patientId = " + savedPatient.getPatientId()
                                    + " Identifier Id = " + savedPatient.getPatientIdentifier().getIdentifier()
                    )
            );
        }
    }

    private void populateUnsavedPatientFromPayload() {
        setPatientIdentifiersFromPayload();
        setPatientBirthDateFromPayload();
        setPatientBirthDateEstimatedFromPayload();
        setPatientGenderFromPayload();
        setPatientNameFromPayload();
        setPatientAddressesFromPayload();
        setPersonAttributesFromPayload();
    }

    private void setPatientIdentifiersFromPayload() {
        Set<PatientIdentifier> patientIdentifiers = new HashSet<PatientIdentifier>();
        PatientIdentifier preferredIdentifier = getPreferredPatientIdentifierFromPayload();
        if (preferredIdentifier != null) {
            patientIdentifiers.add(preferredIdentifier);
        }
        List<PatientIdentifier> otherIdentifiers = getOtherPatientIdentifiersFromPayload();
        if (!otherIdentifiers.isEmpty()) {
            patientIdentifiers.addAll(otherIdentifiers);
        }
        setIdentifierTypeLocation(patientIdentifiers);
        unsavedPatient.setIdentifiers(patientIdentifiers);
    }

    private PatientIdentifier getPreferredPatientIdentifierFromPayload() {
        String identifierValue = JsonUtils.readAsString(payload, "$['patient']['patient.medical_record_number']");
        String identifierTypeName = "AMRS Universal ID";

        PatientIdentifier preferredPatientIdentifier = createPatientIdentifier(identifierTypeName, identifierValue);
        if (preferredPatientIdentifier != null) {
            preferredPatientIdentifier.setPreferred(true);
            return preferredPatientIdentifier;
        } else {
            return null;
        }
    }

    private List<PatientIdentifier> getOtherPatientIdentifiersFromPayload() {
        List<PatientIdentifier> otherIdentifiers = new ArrayList<PatientIdentifier>();
        Object identifierTypeNameObject = JsonUtils.readAsObject(payload, "$['observation']['other_identifier_type']");
        Object identifierValueObject = JsonUtils.readAsObject(payload, "$['observation']['other_identifier_value']");

        if (identifierTypeNameObject instanceof JSONArray) {
            JSONArray identifierTypeName = (JSONArray) identifierTypeNameObject;
            JSONArray identifierValue = (JSONArray) identifierValueObject;
            for (int i = 0; i < identifierTypeName.size(); i++) {
                PatientIdentifier identifier = createPatientIdentifier(identifierTypeName.get(i).toString(),
                        identifierValue.get(i).toString());
                if (identifier != null) {
                    otherIdentifiers.add(identifier);
                }
            }
        } else if (identifierTypeNameObject instanceof String) {
            String identifierTypeName = (String) identifierTypeNameObject;
            String identifierValue = (String) identifierValueObject;
            PatientIdentifier identifier = createPatientIdentifier(identifierTypeName, identifierValue);
            if (identifier != null) {
                otherIdentifiers.add(identifier);
            }
        }
        return otherIdentifiers;
    }

    private PatientIdentifier createPatientIdentifier(String identifierTypeName, String identifierValue) {
        PatientIdentifierType identifierType = Context.getPatientService()
                .getPatientIdentifierTypeByName(identifierTypeName);
        if (identifierType == null) {
            queueProcessorException.addException(
                    new Exception("Unable to find identifier type with name: " + identifierTypeName));
        } else if (identifierValue == null) {
            queueProcessorException.addException(
                    new Exception("Identifier value can't be null type: " + identifierTypeName));
        } else {
            PatientIdentifier patientIdentifier = new PatientIdentifier();
            patientIdentifier.setIdentifierType(identifierType);
            patientIdentifier.setIdentifier(identifierValue);
            return patientIdentifier;
        }
        return null;
    }

    private void setIdentifierTypeLocation(final Set<PatientIdentifier> patientIdentifiers) {
        String locationIdString = JsonUtils.readAsString(payload, "$['encounter']['encounter.location_id']");
        Location location = null;
        int locationId;

        if (locationIdString != null) {
            locationId = Integer.parseInt(locationIdString);
            location = Context.getLocationService().getLocation(locationId);
        }

        if (location == null) {
            queueProcessorException.addException(
                    new Exception("Unable to find encounter location using the id: " + locationIdString));
        } else {
            Iterator<PatientIdentifier> iterator = patientIdentifiers.iterator();
            while (iterator.hasNext()) {
                PatientIdentifier identifier = iterator.next();
                identifier.setLocation(location);
            }
        }
    }

    private void setPatientBirthDateFromPayload() {
        Date birthDate = JsonUtils.readAsDate(payload, "$['patient']['patient.birth_date']");
        unsavedPatient.setBirthdate(birthDate);
    }

    private void setPatientBirthDateEstimatedFromPayload() {
        boolean birthdateEstimated = JsonUtils.readAsBoolean(payload, "$['patient']['patient.birthdate_estimated']");
        unsavedPatient.setBirthdateEstimated(birthdateEstimated);
    }

    private void setPatientGenderFromPayload() {
        String gender = JsonUtils.readAsString(payload, "$['patient']['patient.sex']");
        unsavedPatient.setGender(gender);
    }

    private void setPatientNameFromPayload() {
        String givenName = JsonUtils.readAsString(payload, "$['patient']['patient.given_name']");
        String familyName = JsonUtils.readAsString(payload, "$['patient']['patient.family_name']");
        String middleName = "";
        try {
            middleName = JsonUtils.readAsString(payload, "$['patient']['patient.middle_name']");
        } catch (Exception e) {
            log.error(e);
        }

        PersonName personName = new PersonName();
        personName.setGivenName(givenName);
        personName.setMiddleName(middleName);
        personName.setFamilyName(familyName);
        unsavedPatient.addName(personName);
    }

    private void registerUnsavedPatient() {
        RegistrationDataService registrationDataService = Context.getService(RegistrationDataService.class);
        String temporaryUuid = getPatientUuidFromPayload();
        RegistrationData registrationData = registrationDataService.getRegistrationDataByTemporaryUuid(temporaryUuid);
        if (registrationData == null) {
            registrationData = new RegistrationData();
            registrationData.setTemporaryUuid(temporaryUuid);
            Context.getPatientService().savePatient(unsavedPatient);
            String assignedUuid = unsavedPatient.getUuid();
            registrationData.setAssignedUuid(assignedUuid);
            registrationDataService.saveRegistrationData(registrationData);
        }
    }

    private String getPatientUuidFromPayload() {
        return JsonUtils.readAsString(payload, "$['patient']['patient.uuid']");
    }

    private void setPatientAddressesFromPayload() {
        Set<PersonAddress> addresses = new TreeSet<PersonAddress>();

        try {
            Object patientAddressObject = JsonUtils.readAsObject(payload, "$['patient']['patient.personaddress']");
            if (JsonUtils.isPathAJSONArray(patientAddressObject).equals(true)) {
                //process as JSONArray

                JSONArray jsonArray = (JSONArray) patientAddressObject;
                Iterator iterator = jsonArray.iterator();
                while (iterator.hasNext()) {
                    JSONObject personAddressJSONObject = (JSONObject) iterator.next();

                    String county = (String) personAddressJSONObject.get("countyDistrict");
                    String address6 = (String) personAddressJSONObject.get("address6");
                    String address5 = (String) personAddressJSONObject.get("address5");
                    String cityVillage = (String) personAddressJSONObject.get("cityVillage");

                    PersonAddress patientAddress = new PersonAddress();

                    patientAddress.setStateProvince(county);
                    patientAddress.setAddress6(address6);
                    patientAddress.setAddress5(address5);
                    patientAddress.setCityVillage(cityVillage);

                    addresses.add(patientAddress);

                }
                unsavedPatient.setAddresses(addresses);


            } else if (JsonUtils.isPathAJSONArray(patientAddressObject).equals(false)) {
                //process as JSONObject
                PersonAddress patientAddress = new PersonAddress();
                JSONObject patientAddressJSONObject = (JSONObject) patientAddressObject;
                /**
                 * Extract individual values from JSONObject
                 * Am guessing this section is good to go since it handles scenarios where there is one personaddress object with
                 * non-similar keys.
                 */
                String county = (String) patientAddressJSONObject.get("countyDistrict");
                String address6 = (String) patientAddressJSONObject.get("address6");
                String address5 = (String) patientAddressJSONObject.get("address5");
                String cityVillage = (String) patientAddressJSONObject.get("cityVillage");

                patientAddress.setStateProvince(county);
                patientAddress.setAddress6(address6);
                patientAddress.setAddress5(address5);
                patientAddress.setCityVillage(cityVillage);

                Set<PersonAddress> personAddresses = new TreeSet<PersonAddress>();
                addresses.add(patientAddress);
                unsavedPatient.setAddresses(personAddresses);

            }
        } catch (InvalidPathException ex) {
            Logger.log("The JsonPath", "$['patient']['patient.personaddress'] was not found falling back to patient.personaddress^n nodes.");

            //check is there exists more than on patient.personaddress^n NODES

            JSONObject jsonPayloadObject = (JSONObject) JsonUtils.readAsObject(payload, "$['patient']");
            /**
             * Confirm if payload indeed contains patient.personaddress^n Nodes
             */
            if (JsonUtils.isPersonAddressMultiNode(jsonPayloadObject)) {
                //iterate through the numbered nodes
                JSONArray patientNodeValues = (JSONArray) JsonUtils.readAsObject(payload, "$['patient']");
                Iterator patientNodesIterator = patientNodeValues.iterator();
                for (int i = 1; patientNodesIterator.hasNext(); i++) {
                    try {
                        JSONObject personAddressObject = (JSONObject) JsonUtils.readAsObject(payload, "$['patient']['patient.personaddress^" + i + "']");

                        String county = (String) personAddressObject.get("countyDistrict");
                        String address6 = (String) personAddressObject.get("address6");
                        String address5 = (String) personAddressObject.get("address5");
                        String cityVillage = (String) personAddressObject.get("cityVillage");

                        PersonAddress patientAddress = new PersonAddress();

                        patientAddress.setStateProvince(county);
                        patientAddress.setAddress6(address6);
                        patientAddress.setAddress5(address5);
                        patientAddress.setCityVillage(cityVillage);

                        addresses.add(patientAddress);

                    } catch (InvalidPathException e) {
                        /**
                         * Skip if node is not patient.personaddress^n
                         */
                        Logger.log("JsonRegistrationQueueDataHandler.setPatientAddressesFromPayload()", ex.getMessage());
                    }

                }
                unsavedPatient.setAddresses(addresses);
            }
        }

    }

    private void setPersonAttributesFromPayload() {
        personAttributes = new TreeSet<PersonAttribute>();
        PersonService personService = Context.getPersonService();
        Object personAttributesObject= JsonUtils.readAsObject(payload,"$['patient']['patient.personattribute']");
        /**
         * Check if node contains an array or single JSONObject
         */
        if (JsonUtils.isPathAJSONArray(personAttributesObject)){
            //processing as JSONArray
            JSONArray personAtrributeJsonArray = (JSONArray)personAttributesObject;
            Iterator personAttributesIterator = personAtrributeJsonArray.iterator();
            while (personAttributesIterator.hasNext()){
                JSONObject personAttributeJSONObject = (JSONObject) personAttributesIterator.next();

                String attribute_Type_Uuid = (String)personAttributeJSONObject.get("attribute_type_uuid");
                String attribute_value = (String)personAttributeJSONObject.get("attribute_value");

                //obtain person attribute type  name by uuid
                PersonAttributeType personAttributeType = new PersonAttributeType(new Integer(attribute_Type_Uuid));
                String attributeName = personAttributeType.getName();
                setAsAttribute(attributeName,attribute_value);
            }
        }else{
            //processing as JSONObject
            JSONObject personAttributeJSONObject = (JSONObject)personAttributesObject;
            String attribute_Type_Uuid = (String)personAttributeJSONObject.get("attribute_type_uuid");
            String attribute_value = (String)personAttributeJSONObject.get("attribute_value");

            //obtain person attribute type  name by uuid
            PersonAttributeType personAttributeType = new PersonAttributeType(new Integer(attribute_Type_Uuid));
            String attributeName = personAttributeType.getName();
            setAsAttribute(attributeName,attribute_value);

        }
        unsavedPatient.setAttributes(personAttributes);

        //TODO add facility to process patient.personatrribute^n Nodes
    }

    private void setAsAttribute(String attributeTypeName, String value) {
        PersonService personService = Context.getPersonService();
        PersonAttributeType attributeType = personService.getPersonAttributeTypeByName(attributeTypeName);
        if (attributeType != null && value != null) {
            PersonAttribute personAttribute = new PersonAttribute(attributeType, value);
            personAttributes.add(personAttribute);
        } else if (attributeType == null) {
            queueProcessorException.addException(
                    new Exception("Unable to find Person Attribute type by name '" + attributeTypeName + "'")
            );
        }
    }

    private Patient findSimilarSavedPatient() {
        Patient savedPatient = null;
        if (unsavedPatient.getNames().isEmpty()) {
            PatientIdentifier identifier = unsavedPatient.getPatientIdentifier();
            if (identifier != null) {
                List<Patient> patients = Context.getPatientService().getPatients(identifier.getIdentifier());
                savedPatient = findPatient(patients, unsavedPatient);
            }
        } else {
            PersonName personName = unsavedPatient.getPersonName();
            List<Patient> patients = Context.getPatientService().getPatients(personName.getFullName());
            savedPatient = findPatient(patients, unsavedPatient);
        }
        return savedPatient;
    }

    private Patient findPatient(final List<Patient> patients, final Patient unsavedPatient) {
        for (Patient patient : patients) {
            // match it using the person name and gender, what about the dob?
            PersonName savedPersonName = patient.getPersonName();
            PersonName unsavedPersonName = unsavedPatient.getPersonName();
            if (StringUtils.isNotBlank(savedPersonName.getFullName())
                    && StringUtils.isNotBlank(unsavedPersonName.getFullName())) {
                if (StringUtils.equalsIgnoreCase(patient.getGender(), unsavedPatient.getGender())) {
                    if (patient.getBirthdate() != null && unsavedPatient.getBirthdate() != null
                            && DateUtils.isSameDay(patient.getBirthdate(), unsavedPatient.getBirthdate())) {
                        String savedGivenName = savedPersonName.getGivenName();
                        String unsavedGivenName = unsavedPersonName.getGivenName();
                        int givenNameEditDistance = StringUtils.getLevenshteinDistance(
                                StringUtils.lowerCase(savedGivenName),
                                StringUtils.lowerCase(unsavedGivenName));
                        String savedFamilyName = savedPersonName.getFamilyName();
                        String unsavedFamilyName = unsavedPersonName.getFamilyName();
                        int familyNameEditDistance = StringUtils.getLevenshteinDistance(
                                StringUtils.lowerCase(savedFamilyName),
                                StringUtils.lowerCase(unsavedFamilyName));
                        if (givenNameEditDistance < 3 && familyNameEditDistance < 3) {
                            return patient;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public boolean accept(final QueueData queueData) {
        return StringUtils.equals(DISCRIMINATOR_VALUE, queueData.getDiscriminator());
    }
}
