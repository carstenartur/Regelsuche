package de.regelsuche.discovery.representation;

/** Independent evidence classes for representation-discovery candidates. */
public enum RepresentationCandidateType {
    WHOLE_EXPRESSION_COMPRESSION,
    SUBEXPRESSION_COMPRESSION,
    KNOWN_WHOLE_FORM_BRIDGE,
    KNOWN_SUBFORM_BRIDGE,
    DOWNSTREAM_CAPABILITY_BRIDGE,
    REPEATED_STRUCTURE_EXTRACTION,
    REUSABLE_PARAMETRIC_BRIDGE,
    NO_MATERIAL_REPRESENTATION_GAIN
}
