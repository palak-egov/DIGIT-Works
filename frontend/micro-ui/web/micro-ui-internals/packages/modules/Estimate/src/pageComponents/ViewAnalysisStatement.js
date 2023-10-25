import React,{Fragment, useState} from 'react'
import {
    Card,
    CardSectionHeader,
    LabelFieldPair,
    CardLabel,
    PopUp,
    LinkButton,
    Button,
} from "@egovernments/digit-ui-react-components";
import { useTranslation } from 'react-i18next';

const ViewAnalysisStatement = ({watch,formState,...props}) => {
    const {t} = useTranslation();
    const { register, errors, setValue, getValues, formData } = props
    const [isPopupOpen, setIsPopupOpen] = useState(false);
    //Defined the codes for charges upserted in mdmsV2
    const ChargesCodeMapping = {
        LabourCost : "LH",
        MaterialCost : "MA",
        MachineryCost : "MH",
    }

    //this method is used for calculating labour charges which rate * qty(current Mb entry)
    function getAnalysisCost(category){
        let SORAmount = formData?.SORtable?.reduce((tot,ob) => {
            let amount = ob?.amountDetails?.reduce((total,item) => item?.heads?.includes(category) ? (item?.amount + total) : total,0);
            return (tot + amount * ob?.currentMBEntry)
        },0);
        SORAmount = SORAmount ? SORAmount : 0;
        //Conditions is used in the case of View details to capture the data from additional details
        if(category === "LH" && SORAmount == 0 && formData?.additionalDetails?.labourMaterialAnalysis?.labour) return formData?.additionalDetails?.labourMaterialAnalysis?.labour;
        if(category === "MA" && SORAmount == 0 && formData?.additionalDetails?.labourMaterialAnalysis?.material) return formData?.additionalDetails?.labourMaterialAnalysis?.material;
        if(category === "MH" && SORAmount == 0 && formData?.additionalDetails?.labourMaterialAnalysis?.machinery) return formData?.additionalDetails?.labourMaterialAnalysis?.machinery;
        return (SORAmount).toFixed(2);        
    }
    
   
    
  return (
        <Fragment>
        <LinkButton className="view-Analysis-button" onClick={() => setIsPopupOpen(true)} label={"ESTIMATE_ANALYSIS_STM"}></LinkButton>
        {isPopupOpen && <PopUp>
            <div className="popup-view-alaysis">
            <Card>
            <CardSectionHeader className="estimate-analysis-cardheader">{t(`ESTIMATE_COST_ANALYSIS_HEADER`)}</CardSectionHeader>
            <LabelFieldPair style={{marginBottom:'1rem', marginTop:"5rem", justifyContent:"space-between"}}>
                <CardLabel className="analysis-estimate-label">{`${t(`ESTIMATE_LABOUR_COST`)}`}</CardLabel>
                <CardLabel>{getAnalysisCost(ChargesCodeMapping?.LabourCost)}</CardLabel>
            </LabelFieldPair >
            <LabelFieldPair style={{marginBottom:'1rem', justifyContent:"space-between"}}>
                <CardLabel className="analysis-estimate-label">{`${t(`ESTIMATE_MATERIAL_COST`)}`}</CardLabel>
                <CardLabel>{getAnalysisCost(ChargesCodeMapping?.MaterialCost)}</CardLabel>
            </LabelFieldPair>
            <LabelFieldPair style={{marginBottom:'3rem', justifyContent:"space-between"}}>
                <CardLabel className="analysis-estimate-label">{`${t(`ESTIMATE_MACHINERY_COST`)}`}</CardLabel>
                <CardLabel>{getAnalysisCost(ChargesCodeMapping?.MachineryCost)}</CardLabel>
            </LabelFieldPair>
            <Button
             style={{marginLeft:"85%", width:"16%"}}
             label={"OK"}
             variation="primary"
             onButtonClick={() => {
                setIsPopupOpen(false);
             }}
             type="button"
             />
            </Card>
            </div>
        </PopUp>}
        </Fragment>
  );
}

export default ViewAnalysisStatement;