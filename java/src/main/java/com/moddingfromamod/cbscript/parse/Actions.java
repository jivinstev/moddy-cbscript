// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.moddingfromamod.cbscript.py.PyException;

/**
 * The semantic actions of scriptparse.py ({@code p_*} functions), keyed by function name. They build the program
 * through a {@link Factory}, so the parser package does not depend on the compiler's classes.
 */
public final class Actions {
    /** Builds the compiler's objects; one method per Python constructor the grammar actions call. */
    public interface Factory {
        Object build(String what, int line, Object... args);
    }

    private final Parser parser;
    private final Factory f;
    private final Consumer<String> print;

    Actions(Parser parser, Factory factory, Consumer<String> print) {
        this.parser = parser;
        this.f = factory;
        this.print = print;
    }

    private static PyException syntax(String msg) {
        return new PyException("SyntaxError", msg);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    private static List<Object> cons(Object head, Object tail) {
        List<Object> out = new ArrayList<>();
        out.add(head);
        out.addAll(list(tail));
        return out;
    }

    private static List<Object> one(Object x) {
        List<Object> out = new ArrayList<>();
        out.add(x);
        return out;
    }

    /** validate_mcfunction_name: an uppercase character prints and raises a bare SyntaxError. */
    private void validateName(String name) {
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            i += Character.charCount(c);
            if (com.moddingfromamod.cbscript.lex.PyStr.isUpper(c)) {
                print.accept("\"" + name + "\" is not a valid mcfunction name.");
                PyException e = syntax("");
                e.reprArgs = "";
                throw e;
            }
        }
    }

    private static String strip1(String s) {
        int first = Character.charCount(s.codePointAt(0));
        int last = s.length() - Character.charCount(s.codePointBefore(s.length()));
        return last <= first ? "" : s.substring(first, last);
    }

    void reduce(Tables.Production prod, Parser.P p) {
        String fn = prod.func();
        int n = p.len();
        switch (fn) {
            // ── parsed / program / lib ──
            case "p_parsed_assignment" -> p.set(new Object[] {"program", p.get(1)});
            case "p_parsed_lib" -> p.set(new Object[] {"lib", p.get(2)});
            case "p_parsed_expr" -> p.set(new Object[] {"expr", p.get(1)});
            case "p_program" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dir", p.get(3));
                m.put("desc", p.get(5));
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) p.get(6);
                m.putAll(params);
                m.put("lines", p.get(7));
                p.set(m);
            }
            case "p_lib" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("lines", p.get(1));
                p.set(m);
            }
            case "p_optdesc" -> p.set(n < 4 ? "No Description" : p.get(2));
            case "p_file_params" -> {
                Object[] param = (Object[]) p.get(1);
                @SuppressWarnings("unchecked")
                Map<String, Object> rest = (Map<String, Object>) p.get(2);
                rest.put((String) param[0], param[1]);
                p.set(rest);
            }
            case "p_file_params_none" -> p.set(new LinkedHashMap<String, Object>());
            case "p_file_param" -> {
                if (!p.s(1).equals("scale")) {
                    throw syntax("Unknown file parameter: \"" + p.s(1) + "\" at line " + p.lineno(1));
                }
                p.set(new Object[] {p.s(1), f.build("int", 0, p.get(2))});
            }
            case "p_section_commented" -> p.set(p.get(2));
            case "p_optcomments" -> p.set(cons(p.get(1), p.get(3)));
            case "p_optcomments_empty", "p_macro_args_empty", "p_macro_params_empty", "p_exprlist_empty",
                    "p_macro_call_args_empty", "p_macro_call_params_empty", "p_id_list_empty", "p_top_level_blocks_empty",
                    "p_optcomment_empty", "p_blocklist_empty", "p_else_list_empty", "p_block_cases_empty",
                    "p_opt_block_properties_empty", "p_var_list_empty", "p_block_definition_empty",
                    "p_selector_definition_empty", "p_recipe_lines_empty", "p_recipe_key_list_empty" -> p.set(new ArrayList<>());
            case "p_section", "p_macro_args", "p_function_call_block", "p_method_call_block", "p_template_function_call_block",
                    "p_macro_call_args", "p_macro_call_param_number", "p_block_print", "p_opt_anchor", "p_block_selector_assignment",
                    "p_block_with_anonymous_block", "p_block_function_call", "p_integer", "p_number_integer", "p_float_val",
                    "p_qualifiers_one", "p_qualifier_is", "p_optional_const_value", "p_pyexpr_single", "p_pyexpr_expr_list_one",
                    "p_pyexpr_var", "p_pymap_pair_list_one", "p_virtualinteger_literal", "p_virtualnumber_literal",
                    "p_data_path_id", "p_block_create", "p_nbt_path", "p_nbt_source_object", "p_opt_tile_data",
                    "p_expr_var", "p_json_members", "p_json_value", "p_json_elements",
                    "p_json_literal_element_one", "p_json_literal_value" -> p.set(p.get(1));

            // ── sections ──
            case "p_resetsection" -> p.set(f.build("reset_section", p.lineno(1), p.get(3)));
            case "p_clocksection" -> {
                validateName(p.s(2));
                p.set(f.build("clock_section", p.lineno(1), p.get(2), p.get(4)));
            }
            case "p_functionsection" -> {
                validateName(p.s(2));
                p.set(f.build("function_section", p.lineno(1), p.get(2), p.get(3), p.get(6)));
            }
            case "p_template_function_section" -> {
                validateName(p.s(2));
                p.set(f.build("template_function_section", p.lineno(1), p.get(2), p.get(4), p.get(7), p.get(10)));
            }
            case "p_macrosection" -> p.set(f.build("macro_section", p.lineno(1), p.get(3), p.get(4), p.get(6)));
            case "p_macro_params" -> p.set(cons(p.get(2), p.get(4)));
            case "p_macro_params_one" -> p.set(one(p.get(2)));

            // ── with / calls ──
            case "p_with_macro_item_expr" -> p.set(new Object[] {"expr", p.get(3), p.get(6)});
            case "p_with_macro_item_str" -> p.set(new Object[] {"string", p.get(3), p.get(6)});
            case "p_with_macro_items_one" -> p.set(one(p.get(1)));
            case "p_with_macro_items" -> p.set(cons(p.get(1), p.get(2)));
            case "p_with_macro_items_prefix" -> p.set(f.build("with_items", p.lineno(1), p.get(3)));
            case "p_opt_with_macros_true" -> p.set(f.build("with_items", p.lineno(1), new ArrayList<>()));
            case "p_opt_with_macros_false", "p_opt_anchor_empty" -> p.set(null);
            case "p_exprlist_multiple" -> {
                List<Object> l = list(p.get(1));
                l.add(p.get(3));
                p.set(l);
            }
            case "p_exprlist_single", "p_id_list_one", "p_macro_call_params_one", "p_execute_items_one", "p_conditions_one",
                    "p_block_properties_one", "p_var_list_one" -> p.set(one(p.get(1)));
            case "p_with_anonymous_block" -> p.set(f.build("with_anonymous_block", p.lineno(1), p.get(1), p.get(4)));
            case "p_function_call_namespace" -> p.set(f.build("function_call_block", p.lineno(1),
                    p.s(1) + p.s(2) + p.s(3), p.get(4), p.get(6)));
            case "p_function_call" -> p.set(f.build("function_call_block", p.lineno(1), p.get(1), p.get(2), p.get(4)));
            case "p_function_call_block_with_macro_items", "p_method_call_block_with_macro_items",
                    "p_template_function_call_block_with_macro_items" -> {
                f.build("set_with_macro_items", 0, p.get(2), p.get(1));
                p.set(p.get(2));
            }
            case "p_method_call" -> p.set(f.build("method_call_block", p.lineno(1), p.get(1), p.get(3), p.get(4), p.get(6)));
            case "p_template_function_call" -> p.set(f.build("template_function_call_block", p.lineno(1), p.get(1), p.get(3),
                    p.get(6), p.get(8)));
            case "p_macro_call" -> p.set(f.build("macro_call_block", p.lineno(1), p.get(2), p.get(3)));
            case "p_macro_call_params" -> p.set(cons(p.get(1), p.get(3)));
            case "p_newlines", "p_optnewlines", "p_empty" -> p.set(null);
            case "p_id_list" -> p.set(cons(p.get(1), p.get(3)));
            case "p_top_level_blocks" -> p.set(cons(p.get(1), p.get(3)));
            case "p_top_level_blocks_comment" -> p.set(p.get(3));
            case "p_import_statement" -> p.set(f.build("import_block", p.lineno(1), p.get(2)));
            case "p_python_import_statement" -> {
                if (p.s(4).equals("py")) {
                    p.set(f.build("python_import_block", p.lineno(1), p.get(2)));
                } else if (p.s(4).equals("cblib")) {
                    p.set(f.build("import_block", p.lineno(1), p.get(2)));
                } else {
                    throw syntax("Unknown import file type: \"" + p.s(2) + "." + p.s(4) + "\"");
                }
            }

            // ── variables ──
            case "p_variable_selector" -> p.set(f.build("scoreboard_var", 0, p.get(1), p.get(3)));
            case "p_variable_global" -> p.set(p.s(1).equals("scale") ? f.build("scale_var", 0)
                    : f.build("scoreboard_var", 0, "Global", p.get(1)));
            case "p_variable_array_const" -> p.set(f.build("array_const_var", 0, "Global", p.get(1), p.get(3)));
            case "p_variable_array_expr" -> p.set(f.build("array_expr_var", 0, "Global", p.get(1), p.get(3)));
            case "p_variable_array_const_selector" -> p.set(f.build("array_const_var", 0, p.get(1), p.get(3), p.get(5)));
            case "p_variable_array_expr_selector" -> p.set(f.build("array_expr_var", 0, p.get(1), p.get(3), p.get(5)));
            case "p_variable_block_path_nocoords" -> p.set(f.build("block_path_var", 0, p.get(2), p.get(5), null, new ArrayList<>()));
            case "p_variable_block_path_coords" -> p.set(f.build("block_path_var", 0, p.get(2), p.get(7), p.get(4), new ArrayList<>()));
            case "p_variable_block_macro_path_nocoords" -> p.set(f.build("block_path_var", 0, p.get(2), p.get(5), null, p.get(7)));
            case "p_variable_block_macro_path_coords" -> p.set(f.build("block_path_var", 0, p.get(2), p.get(7), p.get(4), p.get(9)));
            case "p_variable_virtualint" -> p.set(f.build("virtualint_var", 0, p.get(1)));
            case "p_variable_selector_ref" -> p.set(f.build("selector_id_var", 0, p.get(2)));
            case "p_variable_command" -> p.set(f.build("command_var", 0, p.get(1), p.get(3)));
            case "p_variable_storage" -> p.set(f.build("storage_path_var", 0, null, p.get(2)));
            case "p_variable_storage_target" -> p.set(f.build("storage_path_var", 0, p.get(1), p.get(3)));

            // ── block lists ──
            case "p_optcomment" -> p.set(one(f.build("comment_block", p.lineno(1), p.get(1))));
            case "p_blocklist_multiple" -> {
                List<Object> out = new ArrayList<>(list(p.get(2)));
                out.add(p.get(1));
                out.addAll(list(p.get(4)));
                p.set(out);
            }
            case "p_block_comment" -> p.set(f.build("comment_block", p.lineno(1), p.get(1)));
            case "p_block_command" -> p.set(f.build("command_block", p.lineno(1), p.get(1)));
            case "p_block_move" -> p.set(f.build("move_block", p.lineno(1), p.get(2), p.get(3)));
            case "p_block_for" -> p.set(f.build("python_for_block", p.lineno(1), one(p.get(3)), p.get(5), p.get(7)));
            case "p_block_for_tuple" -> p.set(f.build("python_for_block", p.lineno(1), p.get(2), p.get(4), p.get(6)));
            case "p_print_block" -> p.set(f.build("print_block", p.lineno(1), p.get(2)));

            // ── execute ──
            case "p_else_list" -> p.set(cons(p.get(1), p.get(2)));
            case "p_else_item_execute" -> p.set(new Object[] {p.get(2), p.get(4)});
            case "p_else_item_empty" -> p.set(new Object[] {null, p.get(3)});
            case "p_block_if_command" -> p.set(f.build("python_if_block_else_list", p.lineno(1), p.get(2), p.get(4), p.get(5)));
            case "p_execute_as_id_global" -> p.set(f.build("execute_block", p.lineno(1),
                    one(f.build("item", 0, "AsId", new Object[] {p.get(2), null})), p.get(4), p.get(5)));
            case "p_execute_as_id_type_global" -> p.set(f.build("execute_block", p.lineno(1),
                    one(f.build("item", 0, "AsId", new Object[] {p.get(2), p.get(4)})), p.get(7), p.get(8)));
            case "p_execute_as_id_do_global" -> p.set(f.build("execute_block", p.lineno(1),
                    one(f.build("item", 0, "AsId", new Object[] {p.get(2), null})), one(p.get(4)), new ArrayList<>()));
            case "p_execute_as_id_do_type_global" -> p.set(f.build("execute_block", p.lineno(1),
                    one(f.build("item", 0, "AsId", new Object[] {p.get(2), p.get(4)})), one(p.get(7)), new ArrayList<>()));
            case "p_execute_as_create" -> p.set(f.build("execute_block", p.lineno(1),
                    one(f.build("item", 0, "AsCreate", p.get(2))), p.get(4), new ArrayList<>()));
            case "p_execute_as_create_do" -> p.set(f.build("execute_block", p.lineno(1),
                    one(f.build("item", 0, "AsCreate", p.get(2))), one(p.get(4)), new ArrayList<>()));
            case "p_execute_chain" -> p.set(f.build("execute_block", p.lineno(1), p.get(1), p.get(3), p.get(4)));
            case "p_execute_chain_inline" -> p.set(f.build("execute_block", p.lineno(1), p.get(1), one(p.get(3)), new ArrayList<>()));
            case "p_execute_items" -> p.set(cons(p.get(1), p.get(2)));
            case "p_execute_if_condition" -> p.set(f.build("item", 0, "If", p.get(2)));
            case "p_execute_unless_condition" -> p.set(f.build("item", 0, "Unless", p.get(2)));
            case "p_execute_as" -> p.set(f.build("item", 0, "As", p.get(2)));
            case "p_execute_on" -> {
                if (!List.of("attacker", "controller", "leasher", "origin", "owner", "passengers", "target", "vehicle").contains(p.s(2))) {
                    print.accept("Warning: Unknown argument for execute on: \"" + p.s(2) + "\" at line " + p.lineno(2));
                }
                p.set(f.build("item", 0, "On", p.get(2)));
            }
            case "p_execute_rotated" -> p.set(f.build("item", 0, "Rotated", p.get(2)));
            case "p_execute_facing_coords" -> p.set(f.build("item", 0, "FacingCoords", p.get(2)));
            case "p_execute_facing_entity" -> p.set(f.build("item", 0, "FacingEntity", p.get(2)));
            case "p_execute_align" -> {
                if (!List.of("x", "y", "z", "xy", "xz", "yz", "xyz").contains(p.s(2))) {
                    throw syntax("Must align to a combination of x, y, and z axes, not \"" + p.s(2) + "\", at line " + p.lineno(2));
                }
                p.set(f.build("item", 0, "Align", p.get(2)));
            }
            case "p_execute_at_selector" -> p.set(f.build("item", 0, "At", new Object[] {p.get(2), null, p.get(3)}));
            case "p_execute_at_relcoords" -> p.set(f.build("item", 0, "At", new Object[] {null, p.get(3), p.get(2)}));
            case "p_execute_at_selector_relcoords" -> p.set(f.build("item", 0, "At", new Object[] {p.get(2), p.get(4), p.get(3)}));
            case "p_execute_at_vector" -> p.set(f.build("item", 0, "AtVector", new Object[] {null, p.get(2)}));
            case "p_execute_at_vector_scale" -> p.set(f.build("item", 0, "AtVector", new Object[] {p.get(3), p.get(5)}));
            case "p_execute_in_dimension" -> p.set(f.build("item", 0, "In", p.get(2)));
            case "p_for_selector" -> p.set(f.build("for_selector_block", p.lineno(1), p.get(2), p.get(4), p.get(6)));

            // ── conditions ──
            case "p_conditions" -> p.set(cons(p.get(1), p.get(3)));
            case "p_condition_bool_predicate" -> p.set(f.build("condition", 0, "predicate", p.get(2)));
            case "p_condition_fullselector" -> p.set(f.build("condition", 0, "selector", p.get(1)));
            case "p_condition_score" -> {
                String op = p.s(2).equals("==") ? "=" : p.s(2);
                p.set(f.build("condition", 0, "score", f.build("score_condition", 0, p.get(1), op, p.get(3))));
            }
            case "p_condition_vector_equality" -> p.set(f.build("condition", 0, "vector_equality", new Object[] {p.get(1), p.get(3)}));
            case "p_condition_bool" -> p.set(f.build("condition", 0, "score",
                    f.build("score_condition", 0, p.get(1), ">", f.build("virtualint_var", 0, "0"))));
            case "p_condition_not_bool" -> p.set(f.build("condition", 0, "score",
                    f.build("score_condition", 0, p.get(2), "<=", f.build("virtualint_var_int", 0, 0))));
            case "p_opt_block_state" -> p.set(p.s(1) + p.s(2) + p.s(3));
            case "p_opt_block_state_none", "p_opt_tile_data_empty", "p_qualifiers", "p_pyexpr_expr_list_empty",
                    "p_pymap_pair_list_empty", "p_json_members_empty", "p_json_elements_empty",
                    "p_json_literal_elements_empty" -> p.set("");
            case "p_block_states_one" -> p.set(p.s(1) + p.s(2) + p.s(3));
            case "p_block_states_list" -> p.set(p.s(1) + p.s(2) + p.s(3) + p.s(4) + p.s(5));
            case "p_condition_block" -> p.set(f.build("condition", 0, "block",
                    f.build("block_condition", 0, p.get(2), p.s(3) + p.s(4) + p.s(5))));
            case "p_condition_block_virtual" -> p.set(f.build("condition", 0, "block",
                    f.build("block_condition", 0, p.get(2), p.s(3) + p.s(4) + p.s(5) + p.s(6))));
            case "p_condition_block_nocoords" -> p.set(f.build("condition", 0, "block",
                    f.build("block_condition", 0, f.build("relcoords", 0), p.s(2) + p.s(3) + p.s(4))));
            case "p_condition_block_virtual_nocoords" -> p.set(f.build("condition", 0, "block",
                    f.build("block_condition", 0, f.build("relcoords", 0), p.s(2) + p.s(3) + p.s(4) + p.s(5))));
            case "p_condition_nbt_path" -> p.set(f.build("condition", 0, "nbt_path", p.get(1)));

            // ── control flow ──
            case "p_block_ifelse_command" -> p.set(f.build("python_if_block", p.lineno(1), p.get(2), p.get(4), p.get(7)));
            case "p_block_while" -> p.set(f.build("while_block", p.lineno(1), one(f.build("item", 0, "If", p.get(2))), p.get(4)));
            case "p_block_while_execute" -> {
                List<Object> items = one(f.build("item", 0, "If", p.get(2)));
                items.addAll(list(p.get(3)));
                p.set(f.build("while_block", p.lineno(1), items, p.get(5)));
            }
            case "p_block_for_index_by" -> p.set(f.build("for_index_block", p.lineno(1), p.get(2), p.get(4), p.get(6), p.get(8), p.get(10)));
            case "p_block_for_index" -> p.set(f.build("for_index_block", p.lineno(1), p.get(2), p.get(4), p.get(6), null, p.get(8)));
            case "p_switch" -> p.set(f.build("switch_block", p.lineno(1), p.get(2), p.get(4)));
            case "p_cases_one" -> p.set(one(p.get(1)));
            case "p_cases_multiple" -> p.set(cons(p.get(1), p.get(3)));
            case "p_switch_case_single" -> p.set(f.build("switch_case", p.lineno(1), "range", new Object[] {p.get(2), p.get(2), p.get(4)}));
            case "p_switch_case_range" -> p.set(f.build("switch_case", p.lineno(1), "range", new Object[] {p.get(2), p.get(4), p.get(6)}));
            case "p_switch_case_python" -> p.set(f.build("switch_case", p.lineno(1), "python", new Object[] {p.get(3), p.get(5), p.get(7)}));
            case "p_block_switch" -> p.set(f.build("block_switch_block", p.lineno(1), p.get(3), p.get(5), false));
            case "p_block_id_switch" -> p.set(f.build("block_id_switch_block", p.lineno(1), p.get(3), p.get(5), false));
            case "p_block_data_switch" -> p.set(f.build("block_switch_block", p.lineno(1), p.get(3), p.get(5), true));
            case "p_block_data_id_switch" -> p.set(f.build("block_id_switch_block", p.lineno(1), p.get(3), p.get(5), true));
            case "p_opt_coords" -> p.set(p.get(2));
            case "p_opt_coords_empty", "p_block_coords_empty" -> p.set(f.build("relcoords", 0));
            case "p_block_cases_multi" -> p.set(cons(p.get(1), p.get(3)));
            case "p_block_case_default" -> p.set(f.build("block_case", 0, null, null, p.get(3), true));
            case "p_block_case_specific" -> p.set(f.build("block_case", 0, p.get(2), p.get(3), p.get(5), false));
            case "p_opt_block_properties" -> p.set(p.get(2));
            case "p_block_property" -> p.set(new String[] {p.s(1), p.s(3)});
            case "p_block_properties_multi" -> p.set(cons(p.get(1), p.get(3)));

            // ── tell / title / push / pop ──
            case "p_block_tell" -> p.set(f.build("tell_block", p.lineno(1), p.get(2), p.get(3)));
            case "p_block_title" -> p.set(f.build("title_block", p.lineno(1), p.get(1), p.get(2), null, p.get(3)));
            case "p_block_title_times" -> p.set(f.build("title_block", p.lineno(1), p.get(1), p.get(2),
                    new Object[] {p.get(3), p.get(4), p.get(5)}, p.get(6)));
            case "p_block_push" -> p.set(f.build("push_block", p.lineno(1), p.get(2)));
            case "p_var_list" -> p.set(cons(p.get(1), p.get(3)));
            case "p_block_pop" -> p.set(f.build("pop_block", p.lineno(1), p.get(2)));

            // ── literals ──
            case "p_string" -> p.set(strip1(p.s(1)));
            case "p_integer_minus" -> p.set(f.build("neg_int_str", 0, p.get(2)));
            case "p_float_val_minus" -> p.set(f.build("neg_float_str", 0, p.get(2)));
            case "p_qualifiers_multiple" -> p.set(p.s(1) + "," + p.s(3));
            case "p_qualifier_binop" -> p.set(p.s(1) + p.s(2) + p.s(3));
            case "p_qualifier_builtin" -> {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i < n; i++) {
                    b.append(p.s(i));
                }
                p.set(b.toString());
            }
            case "p_qualifier_empty" -> p.set(p.s(1) + p.s(2));
            case "p_qualifier_is_not" -> p.set(p.s(1) + p.s(2) + p.s(3) + p.s(4));
            case "p_qualifier_not" -> p.set("not " + p.s(2));
            case "p_fullselector" -> p.set("@" + p.s(1) + "[]");
            case "p_fullselector_qualifiers" -> p.set("@" + p.s(1) + "[" + p.s(3) + "]");

            // ── coordinates ──
            case "p_relcoord_number" -> p.set(f.build("relcoord", 0, "", p.get(1)));
            case "p_relcoord_relnumber" -> p.set(f.build("relcoord", 0, "~", p.get(2)));
            case "p_relcoord_relzero" -> p.set(f.build("relcoord", 0, "~", f.build("const_string", 0, "")));
            case "p_optional_const_value_empty" -> p.set(f.build("const_string", 0, ""));
            case "p_localcoord_localnumber" -> p.set(f.build("relcoord", 0, "^", p.get(2)));
            case "p_relcoords" -> p.set(f.build("relcoords3", 0, p.get(1), p.get(2), p.get(3)));
            case "p_relcoords_vector_relative" -> p.set(f.build("relcoord_vector", p.lineno(1), p.get(1), p.get(2)));
            case "p_relcoords_vector" -> p.set(f.build("relcoord_vector", p.lineno(1), "", p.get(1)));

            // ── compile-time Python ──
            case "p_const_value_interpreted" -> p.set(f.build("interpreted_python", p.lineno(1), p.get(2)));
            case "p_const_value_expr" -> p.set(f.build("interpreted_python", p.lineno(1), p.get(1)));
            case "p_const_vector" -> p.set(f.build("interpreted_python", p.lineno(1), p.get(2)));
            case "p_pyexpr_binop" -> p.set(p.s(1) + p.s(2) + p.s(3));
            case "p_pyexpr_binop_double" -> p.set(p.s(1) + p.s(2) + p.s(3) + p.s(4));
            case "p_pyexpr_binop_spaced" -> p.set(p.s(1) + " " + p.s(2) + " " + p.s(3));
            case "p_pyexpr_unary" -> p.set(p.s(1) + p.s(2));
            case "p_pyexpr_expr_list_multi", "p_pymap_pair_list" -> p.set(p.s(1) + p.s(2) + p.s(4));
            case "p_pyexpr_array" -> p.set(p.s(1) + p.s(3) + p.s(5));
            case "p_pyexpr_function_call" -> p.set(p.s(2) + "(" + p.s(4) + p.s(6));
            case "p_pyexpr_array_lookup" -> p.set(p.s(1) + p.s(2) + p.s(3) + p.s(4));
            case "p_pyexpr_member" -> p.set(p.s(1) + p.s(2) + p.s(3));
            case "p_pyexpr_pyid" -> p.set(p.get(2));
            case "p_pymap_pair" -> p.set(p.s(1) + p.s(2) + p.s(4));
            case "p_pyexpr_map" -> p.set(p.s(1) + p.s(3) + p.s(5));
            case "p_virtualinteger_symbol", "p_virtualnumber_symbol" -> p.set("$" + p.s(2));

            // ── tags ──
            case "p_blocktag" -> p.set(f.build("block_tag_block", p.lineno(1), p.get(3), p.get(5)));
            case "p_entitytag" -> p.set(f.build("entity_tag_block", p.lineno(1), p.get(3), p.get(5)));
            case "p_itemtag" -> p.set(f.build("item_tag_block", p.lineno(1), p.get(3), p.get(5)));
            case "p_block_list", "p_entity_list", "p_item_list" -> p.set(cons(p.get(1), p.get(3)));
            case "p_block_list_one", "p_entity_list_one", "p_item_list_one" -> p.set(one(p.get(1)));

            // ── definitions ──
            case "p_pointer_decl" -> p.set(f.build("pointer_decl_block", p.lineno(0), p.get(1), p.get(3)));
            case "p_block_define_block" -> p.set(f.build("block_definition_block", p.lineno(1), p.get(3), p.get(6), f.build("relcoords", 0)));
            case "p_block_define_block_coords" -> p.set(f.build("block_definition_block", p.lineno(1), p.get(3), p.get(8), p.get(6)));
            case "p_block_definition", "p_selector_definition", "p_recipe_lines", "p_recipe_key_list" -> p.set(cons(p.get(1), p.get(3)));
            case "p_block_item_path_noscale" -> p.set(f.build("number_macro_path", 0, p.get(1), new ArrayList<>(), p.get(3), p.get(4), null));
            case "p_block_item_path_scale" -> p.set(f.build("number_macro_path", 0, p.get(1), new ArrayList<>(), p.get(3), p.get(4), p.get(5)));
            case "p_block_item_macro_path_noscale" -> p.set(f.build("number_macro_path", 0, p.get(1), p.get(3), p.get(6), p.get(7), null));
            case "p_block_item_macro_path_scale" -> p.set(f.build("number_macro_path", 0, p.get(1), p.get(3), p.get(6), p.get(7), p.get(8)));
            case "p_uuid" -> p.set(f.build("uuid", 0, p.get(1), p.get(3), p.get(5), p.get(7), p.get(9)));
            case "p_selector_assignment" -> p.set(f.build("selector_assignment_block", p.lineno(1), p.get(1), p.get(3)));
            case "p_selector_define" -> p.set(f.build("selector_definition_block", p.lineno(1), p.get(2), p.get(4), null, p.get(6)));
            case "p_selector_define_uuid" -> p.set(f.build("selector_definition_block", p.lineno(1), p.get(2), p.get(6), p.get(4), p.get(9)));
            case "p_selector_item_tag" -> p.set(f.build("selector_item", 0, "Tag", new Object[] {p.get(2)}));
            case "p_selector_item_path_scale" -> p.set(f.build("selector_item", 0, "Path", new Object[] {p.get(1), p.get(3), p.get(4), p.get(5)}));
            case "p_selector_item_path" -> p.set(f.build("selector_item", 0, "Path", new Object[] {p.get(1), p.get(3), p.get(4), null}));
            case "p_selector_item_vector_path_scale" -> p.set(f.build("selector_item", 0, "VectorPath",
                    new Object[] {p.get(2), p.get(5), p.get(6), p.get(7)}));
            case "p_selector_item_vector_path" -> p.set(f.build("selector_item", 0, "VectorPath", new Object[] {p.get(2), p.get(5), p.get(6), null}));
            case "p_selector_item_method" -> p.set(f.build("selector_item", 0, "Method", new Object[] {p.get(1)}));
            case "p_selector_pointer" -> p.set(f.build("selector_item", 0, "Pointer", new Object[] {p.get(1), p.get(3)}));
            case "p_selector_array" -> {
                f.build("set_selector_based", 0, p.get(1));
                p.set(f.build("selector_item", 0, "Array", new Object[] {p.get(1)}));
            }
            case "p_selector_predicate" -> p.set(f.build("selector_item", 0, "Predicate", new Object[] {p.get(1)}));
            case "p_data_path_array" -> p.set(p.s(1) + "[" + p.s(3) + "]");
            case "p_data_path_array_match" -> p.set(p.s(1) + p.s(2) + p.s(3) + p.s(4));
            case "p_data_path_object_match" -> p.set(p.s(1) + p.s(2));
            case "p_data_path_multi" -> p.set(p.s(1) + "." + p.s(3));
            case "p_data_type" -> {
                if (!List.of("byte", "double", "float", "int", "long", "short").contains(p.s(1))) {
                    throw syntax("Syntax Error: Invalid path type \"{}\" at line " + p.lineno(1) + ".");
                }
                p.set(p.get(1));
            }
            case "p_array_definition" -> p.set(f.build("array_definition_block", p.lineno(1), p.get(2), f.build("const_number0", 0), p.get(4), false));
            case "p_array_definition_range" -> p.set(f.build("array_definition_block", p.lineno(1), p.get(2), p.get(4), p.get(6), false));
            case "p_create" -> p.set(f.build("create_block", p.lineno(1), p.get(2), p.get(3), null));
            case "p_create_nocoords" -> p.set(f.build("create_block", p.lineno(1), p.get(2), f.build("relcoords", 0), null));
            case "p_create_index" -> p.set(f.build("create_block", p.lineno(1), p.get(2), p.get(6), p.get(4)));
            case "p_create_index_nocoords" -> p.set(f.build("create_block", p.lineno(1), p.get(2), f.build("relcoords", 0), p.get(4)));
            case "p_define_name" -> p.set(f.build("define_name_block", p.lineno(1), p.get(3), strip1(p.s(5))));
            case "p_pythonassignment_interpreted_string" -> p.set(f.build("python_assignment_block", p.lineno(1), p.get(2), p.get(4)));
            case "p_python_tuple_two" -> {
                List<Object> l = one(p.get(2));
                l.add(p.get(5));
                p.set(l);
            }
            case "p_python_tuple_multi" -> p.set(cons(p.get(2), p.get(4)));
            case "p_python_tuple_assignment" -> p.set(f.build("python_tuple_assignment_block", p.lineno(1), p.get(1), p.get(3)));

            // ── nbt ──
            case "p_nbt_assignment" -> p.set(f.build("nbt_data_block", p.lineno(1), p.get(1), "set", p.get(3)));
            case "p_nbt_merge" -> p.set(f.build("nbt_data_block", p.lineno(1), p.get(1), "merge", p.get(3)));
            case "p_nbt_append" -> p.set(f.build("nbt_data_block", p.lineno(1), p.get(1), "append", p.get(3)));
            case "p_nbt_list_entity", "p_nbt_object_entity" -> p.set(f.build("entity_nbt_path", 0, p.get(1), p.get(4)));
            case "p_nbt_list_storage", "p_nbt_object_storage" -> p.set(f.build("storage_nbt_path", 0, null, p.get(3)));
            case "p_nbt_list_storage_target", "p_nbt_object_storage_target" -> p.set(f.build("storage_nbt_path", 0, p.get(1), p.get(4)));
            case "p_nbt_list_block", "p_nbt_object_block" -> p.set(f.build("block_nbt_path", 0, p.get(1), p.get(4)));
            case "p_nbt_source_path_entity" -> p.set(f.build("entity_nbt_path", 0, p.get(1), p.get(3)));
            case "p_nbt_source_path_storage" -> p.set(f.build("storage_nbt_path", 0, null, p.get(2)));
            case "p_nbt_source_path_storage_target" -> p.set(f.build("storage_nbt_path", 0, p.get(1), p.get(3)));
            case "p_nbt_source_path_block" -> p.set(f.build("block_nbt_path", 0, p.get(1), p.get(3)));
            case "p_nbt_source_json", "p_nbt_source_string" -> p.set(f.build("nbt_json", 0, p.get(1)));
            case "p_block_coords" -> p.set(p.get(2));
            case "p_nbt_remove" -> p.set(f.build("nbt_remove_block", p.lineno(1), p.get(2)));

            // ── assignment ──
            case "p_return_expression" -> p.set(f.build("scoreboard_assignment_block", p.lineno(1),
                    f.build("scoreboard_var", 0, "Global", "ReturnValue"), "=", p.get(2)));
            case "p_assignment" -> p.set(f.build("scoreboard_assignment_block", p.lineno(1), p.get(1), p.get(2), p.get(3)));
            case "p_assignment_unary_default" -> p.set(f.build("scoreboard_assignment_block", p.lineno(1), p.get(1),
                    p.s(2).substring(0, 1) + "=", f.build("virtualint_var", 0, "1")));
            case "p_assignment_create" -> p.set(f.build("scoreboard_assignment_block", p.lineno(1), p.get(1), p.get(2),
                    f.build("create_expr", 0, p.get(3))));
            case "p_vector_assignment_vector" -> p.set(f.build("vector_assignment_block", p.lineno(1), p.get(1), p.get(2), p.get(3)));
            case "p_vector_assignment_scalar" -> p.set(f.build("vector_assignment_scalar_block", p.lineno(1), p.get(1), p.get(2), p.get(3)));
            case "p_vector_var_id" -> p.set(f.build("vector_var", 0, "VAR_ID", p.get(2)));
            case "p_vector_var_sel_id" -> p.set(f.build("vector_var", 0, "SEL_VAR_ID", new String[] {p.s(1), p.s(4)}));
            case "p_vector_var_components" -> {
                List<Object> c = new ArrayList<>();
                c.add(p.get(2));
                c.add(p.get(4));
                c.add(p.get(6));
                p.set(f.build("vector_var", 0, "VAR_COMPONENTS", c));
            }
            case "p_vector_var_const_vector" -> p.set(f.build("vector_var", 0, "VAR_CONST", p.get(1)));

            // ── expressions ──
            case "p_expr_binary" -> p.set(f.build("binop_expr", 0, p.get(1), p.get(2), p.get(3)));
            case "p_expr_power" -> p.set(f.build("binop_expr", 0, p.get(1), p.get(2), f.build("virtualint_var", 0, p.get(3))));
            case "p_expr_dot" -> p.set(f.build("dot_expr", 0, p.get(1), p.get(3)));
            case "p_expr_function" -> p.set(f.build("func_expr", 0, p.get(1)));
            case "p_expr_method" -> p.set(f.build("method_expr", 0, p.get(1)));
            case "p_expr_group", "p_vector_expr_group" -> p.set(p.get(2));
            case "p_expr_unary" -> p.set(f.build("unary_expr", 0, "-", p.get(2)));
            case "p_vector_expr_vector_triplet" -> p.set(f.build("vector_expr", 0, p.get(2), p.get(4), p.get(6)));
            case "p_vector_expr_vector_unit" -> p.set(f.build("vector_var_expr", 0, p.get(2)));
            case "p_vector_expr_const_vector" -> p.set(f.build("vector_var_const_vector", 0, p.get(1)));
            case "p_vector_expr_selector_vector" -> p.set(f.build("sel_vector_var_expr", 0, p.get(1), p.get(4)));
            case "p_vector_expr_binop_vector" -> p.set(f.build("vector_binop_vector_expr", 0, p.get(1), p.get(2), p.get(3)));
            case "p_vector_expr_binop_scalar" -> p.set(f.build("vector_binop_scalar_expr", 0, p.get(1), p.get(2), p.get(3)));
            case "p_vector_expr_binop_scalar_reversed" -> p.set(f.build("vector_binop_scalar_expr", 0, p.get(3), p.get(2), p.get(1)));
            case "p_vector_expr_negative" -> p.set(f.build("vector_binop_scalar_expr", 0, p.get(2), "*", f.build("virtualint_var_int", 0, -1)));
            case "p_vector_expr_here" -> p.set(f.build("vector_here_expr", 0, (Object) null));
            case "p_vector_expr_here_scale" -> p.set(f.build("vector_here_expr", 0, p.get(3)));

            // ── json ──
            case "p_json_object", "p_json_array" -> p.set(p.s(1) + p.s(3) + p.s(5));
            case "p_json_members_multi", "p_json_elements_multi", "p_json_literal_elements_multi" -> p.set(p.s(1) + p.s(2) + p.s(4));
            case "p_json_pair" -> p.set("\"" + p.s(1) + "\"" + p.s(2) + p.s(4));
            case "p_json_value_dollar_id", "p_json_literal_value_dollar_id" -> p.set(p.s(1) + p.s(2));
            case "p_json_value_typed_number" -> {
                if (!List.of("b", "f", "s", "d", "l").contains(p.s(2).toLowerCase(java.util.Locale.ROOT))) {
                    throw syntax("Invalid type \"" + p.s(2) + "\" for number \"" + p.s(1) + "\" at line " + p.lineno(1));
                }
                p.set(p.s(1) + p.s(2));
            }
            case "p_json_value_string" -> p.set("\"" + p.s(1) + "\"");
            case "p_json_literal_array" -> {
                if (!List.of("b", "i", "l").contains(p.s(3).toLowerCase(java.util.Locale.ROOT))) {
                    throw syntax("Invalid type \"" + p.s(3) + "\" for literal array at line " + p.lineno(1));
                }
                p.set(p.s(1) + p.s(3) + p.s(4) + p.s(6) + p.s(8));
            }

            // ── recipes, advancements, loot tables, predicates, item modifiers ──
            case "p_shaped_recipe" -> p.set(f.build("shaped_recipe_block", p.lineno(1), p.get(4), p.get(7), p.get(12), p.get(13)));
            case "p_recipe_key_item" -> {
                if (p.s(1).codePointCount(0, p.s(1).length()) != 1) {
                    throw syntax("Recipe key \"" + p.s(1) + "\" at line " + p.lineno(1) + " is not a single character.");
                }
                if (!p.s(3).equals("item") && !p.s(3).equals("tag")) {
                    throw syntax("Recipe key type \"" + p.s(3) + "\" at line " + p.lineno(1) + " must be \"block\" or \"tag\".");
                }
                p.set(new String[] {p.s(1), p.s(3), p.s(5)});
            }
            case "p_advancement_definition" -> p.set(f.build("advancement_definition_block", p.lineno(1), p.get(2), p.get(3)));
            case "p_loot_table_type" -> {
                if (!p.s(1).equals("block")) {
                    throw syntax("Invalid loot table type \"" + p.s(1) + "\" at line " + p.lineno(1) + ".");
                }
                p.set("blocks");
            }
            case "p_loot_table_definition" -> p.set(f.build("loot_table_definition_block", p.lineno(1), p.get(2),
                    p.s(3) + p.s(4) + p.s(5), p.get(6)));
            case "p_predicate_definition" -> p.set(f.build("predicate_definition_block", p.lineno(1), p.get(2), p.get(3)));
            case "p_item_modifier_definition" -> p.set(f.build("item_modifier_definition_block", p.lineno(1), p.get(2), p.get(3)));
            default -> throw new IllegalStateException("no Java action for " + fn + " (" + prod.text() + ")");
        }
    }
}
